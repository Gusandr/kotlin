/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.backend.generators

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.backend.*
import org.jetbrains.kotlin.fir.backend.conversion.withScopeAndParent
import org.jetbrains.kotlin.fir.containingClassLookupTag
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.impl.FirDefaultPropertyGetter
import org.jetbrains.kotlin.fir.declarations.impl.FirDefaultPropertySetter
import org.jetbrains.kotlin.fir.declarations.utils.isExpect
import org.jetbrains.kotlin.fir.declarations.utils.isFromEnumClass
import org.jetbrains.kotlin.fir.dispatchReceiverClassLookupTagOrNull
import org.jetbrains.kotlin.fir.expressions.FirDelegatedConstructorCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.impl.FirNoReceiverExpression
import org.jetbrains.kotlin.fir.references.toResolvedConstructorSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFieldAccessExpression
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.constructedClassType
import org.jetbrains.kotlin.ir.util.isSetter
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.withScope
import org.jetbrains.kotlin.resolve.DataClassResolver

internal class ClassMemberGenerator(private val components: Fir2IrComponents) : Fir2IrComponents by components {
    private val visitor = Fir2IrVisitor(components)

    private fun FirTypeRef.toIrType(): IrType = with(typeConverter) { toIrType() }

    private fun ConeKotlinType.toIrType(): IrType = with(typeConverter) { toIrType() }

    private fun <T : IrDeclaration> applyParentFromStackTo(declaration: T): T = conversionScope.applyParentFromStackTo(declaration)

    fun <T : IrFunction> convertFunctionContent(irFunction: T, firFunction: FirFunction?, containingClass: FirClass?) {
        if (firFunction == null) return

        with(irFunction) {
            if (irFunction !is IrConstructor || !irFunction.isPrimary) {
                val irParameters = valueParameters.drop(firFunction.contextReceivers.size)
                val annotationMode = containingClass?.classKind == ClassKind.ANNOTATION_CLASS && irFunction is IrConstructor
                for ((valueParameter, firValueParameter) in irParameters.zip(firFunction.valueParameters)) {
                    visitor.withAnnotationMode(enableAnnotationMode = annotationMode) {
                        valueParameter.setDefaultValue(firValueParameter)
                    }
                }
                annotationGenerator.generate(irFunction, firFunction)
            }
            if (firFunction is FirConstructor && irFunction is IrConstructor && !firFunction.isExpect) {
                val body = factory.createBlockBody(startOffset, endOffset)
                val delegatedConstructor = firFunction.delegatedConstructor
                val irClass = parent as IrClass
                if (delegatedConstructor != null) {
                    val irDelegatingConstructorCall = conversionScope.forDelegatingConstructorCall(irFunction, irClass) {
                        delegatedConstructor.toIrDelegatingConstructorCall()
                    }
                    body.statements += irDelegatingConstructorCall
                }

                if (containingClass is FirRegularClass && containingClass.contextReceivers.isNotEmpty()) {
                    val contextReceiverFields =
                        components.classifierStorage.getFieldsWithContextReceiversForClass(irClass)
                            ?: error("Not found context receiver fields")

                    val thisParameter =
                        conversionScope.dispatchReceiverParameter(irClass.symbol) ?: error("No found this parameter for $irClass")

                    for (index in containingClass.contextReceivers.indices) {
                        val irValueParameter = valueParameters[index]
                        body.statements.add(
                            IrSetFieldImpl(
                                UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                                contextReceiverFields[index].symbol,
                                IrGetValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, thisParameter.type, thisParameter.symbol),
                                IrGetValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, irValueParameter.type, irValueParameter.symbol),
                                components.irBuiltIns.unitType,
                            )
                        )
                    }
                }

                if (delegatedConstructor?.isThis == false) {
                    val instanceInitializerCall = IrInstanceInitializerCallImpl(
                        startOffset, endOffset, irClass.symbol, irFunction.constructedClassType
                    )
                    body.statements += instanceInitializerCall
                }

                val regularBody = firFunction.body?.let { visitor.convertToIrBlockBody(it) }
                if (regularBody != null) {
                    body.statements += regularBody.statements
                }
                if (body.statements.isNotEmpty()) {
                    irFunction.body = body
                }
            } else if (irFunction !is IrConstructor && !irFunction.isExpect) {
                when {
                    // Create fake bodies for Enum.values/Enum.valueOf
                    irFunction.origin == IrDeclarationOrigin.ENUM_CLASS_SPECIAL_MEMBER -> {
                        val name = (irFunction as? IrSimpleFunction)?.correspondingPropertySymbol?.owner?.name ?: irFunction.name
                        val kind = Fir2IrDeclarationStorage.ENUM_SYNTHETIC_NAMES.getValue(name)
                        irFunction.body = IrSyntheticBodyImpl(startOffset, endOffset, kind)
                    }
                    irFunction.parent is IrClass && irFunction.parentAsClass.isData -> {
                        val lookupTag = firFunction.symbol.dispatchReceiverClassLookupTagOrNull()
                        when {
                            DataClassResolver.isComponentLike(irFunction.name) ->
                                firFunction.body?.let { irFunction.body = visitor.convertToIrBlockBody(it) }
                                    ?: DataClassMembersGenerator(components).generateDataClassComponentBody(irFunction)
                            DataClassResolver.isCopy(irFunction.name) ->
                                firFunction.body?.let { irFunction.body = visitor.convertToIrBlockBody(it) }
                                    ?: DataClassMembersGenerator(components).generateDataClassCopyBody(irFunction)
                            else ->
                                irFunction.body = firFunction.body?.let { visitor.convertToIrBlockBody(it) }
                        }
                    }
                    else -> {
                        irFunction.body = firFunction.body?.let { visitor.convertToIrBlockBody(it) }
                    }
                }
            }
            if (irFunction is IrSimpleFunction && firFunction is FirSimpleFunction && containingClass != null) {
                irFunction.overriddenSymbols = firFunction.generateOverriddenFunctionSymbols(containingClass)
            }
        }
    }

    fun convertPropertyContent(irProperty: IrProperty, property: FirProperty, containingClass: FirClass?): IrProperty {
        val initializer = property.backingField?.initializer ?: property.initializer
        val delegate = property.delegate
        val propertyType = property.returnTypeRef.toIrType()
        irProperty.initializeBackingField(property, initializerExpression = initializer ?: delegate)
//        if (containingClass != null) {
//            irProperty.overriddenSymbols = property.generateOverriddenPropertySymbols(containingClass)
//        }
        val needGenerateDefaultGetter =
            property.getter is FirDefaultPropertyGetter ||
                    (property.getter == null && irProperty.parent is IrScript && property.destructuringDeclarationContainerVariable != null)

        irProperty.getter?.setPropertyAccessorContent(
            property, property.getter, irProperty, propertyType,
            isDefault = needGenerateDefaultGetter,
            isGetter = true,
            containingClass = containingClass
        )
        // Create fake body for Enum.entries
        if (irProperty.origin == IrDeclarationOrigin.ENUM_CLASS_SPECIAL_MEMBER) {
            val kind = Fir2IrDeclarationStorage.ENUM_SYNTHETIC_NAMES.getValue(irProperty.name)
            irProperty.getter!!.body = IrSyntheticBodyImpl(irProperty.startOffset, irProperty.endOffset, kind)
        }

        if (property.isVar) {
            irProperty.setter?.setPropertyAccessorContent(
                property, property.setter, irProperty, propertyType,
                property.setter is FirDefaultPropertySetter,
                isGetter = false,
                containingClass = containingClass
            )
        }
        annotationGenerator.generate(irProperty, property)
        return irProperty
    }

    fun convertFieldContent(irField: IrField, field: FirField): IrField {
        conversionScope.withParent(irField) {
            declarationStorage.enterScope(irField)
            val initializerExpression = field.initializer
            if (irField.initializer == null && initializerExpression != null) {
                irField.initializer = irFactory.createExpressionBody(visitor.convertToIrExpression(initializerExpression))
            }
            declarationStorage.leaveScope(irField)
        }
        return irField
    }

    private fun IrProperty.initializeBackingField(property: FirProperty, initializerExpression: FirExpression?) {
        val irField = backingField ?: return
        if (irField.initializer == null && initializerExpression != null) {
            conversionScope.withScopeAndParent(irField) {
                var irInitializer = visitor.convertToIrExpression(initializerExpression, isDelegate = property.delegate != null)
                if (property.delegate == null) {
                    with(visitor.implicitCastInserter) {
                        irInitializer = irInitializer.cast(initializerExpression, initializerExpression.typeRef, property.returnTypeRef)
                    }
                }
                irField.initializer = irFactory.createExpressionBody(irInitializer)
            }
        }

        property.backingField?.let { annotationGenerator.generate(irField, it) }
    }

    private fun IrSimpleFunction.setPropertyAccessorContent(
        property: FirProperty,
        propertyAccessor: FirPropertyAccessor?,
        correspondingProperty: IrProperty,
        propertyType: IrType,
        isDefault: Boolean,
        isGetter: Boolean,
        containingClass: FirClass?
    ) {

        conversionScope.withFunction(this) {
            applyParentFromStackTo(this)
            conversionScope.withParent(this) {
                convertFunctionContent(this, propertyAccessor, containingClass = null)
                if (isDefault) {
                    generateDefaultPropertyAccessorBody(this, correspondingProperty, propertyType)
                }
            }
//            if (containingClass != null) {
//                this.overriddenSymbols = property.generateOverriddenAccessorSymbols(containingClass, isGetter)
//            }
        }
    }

    private fun generateDefaultPropertyAccessorBody(accessor: IrSimpleFunction, correspondingProperty: IrProperty, propertyType: IrType) {
        val backingField = correspondingProperty.backingField
        val fieldSymbol = backingField?.symbol ?: return
        conversionScope.withScopeAndParent(accessor) {
            val expressionBody = if (accessor.isSetter) {
                IrSetFieldImpl(accessor.startOffset, accessor.endOffset, fieldSymbol, irBuiltIns.unitType).apply {
                    setReceiver(accessor)
                    value = IrGetValueImpl(startOffset, endOffset, propertyType, accessor.valueParameters.first().symbol)
                }
            } else {
                IrReturnImpl(
                    accessor.startOffset, accessor.endOffset, irBuiltIns.nothingType, accessor.symbol,
                    IrGetFieldImpl(accessor.startOffset, accessor.endOffset, fieldSymbol, propertyType).apply {
                        setReceiver(accessor)
                    }
                )
            }
            accessor.body = accessor.factory.createBlockBody(accessor.startOffset, accessor.endOffset, listOf(expressionBody))
        }
    }

    fun convertAnonymousInitializerContent(
        irAnonymousInitializer: IrAnonymousInitializer,
        anonymousInitializer: FirAnonymousInitializer,
    ) {
        symbolTable.withScope(irAnonymousInitializer) {
            irAnonymousInitializer.body = visitor.convertToIrBlockBody(anonymousInitializer.body!!)
        }
    }

    private fun IrFieldAccessExpression.setReceiver(declaration: IrDeclaration) {
        if (declaration is IrFunction) {
            val dispatchReceiver = declaration.dispatchReceiverParameter
            if (dispatchReceiver != null) {
                receiver = IrGetValueImpl(startOffset, endOffset, dispatchReceiver.symbol)
            }
        }
    }

    internal fun FirDelegatedConstructorCall.toIrDelegatingConstructorCall(): IrExpression {
        val constructedIrType = constructedTypeRef.toIrType()
        val referencedSymbol = calleeReference.toResolvedConstructorSymbol()
            ?: return convertWithOffsets { startOffset, endOffset ->
                IrErrorCallExpressionImpl(
                    startOffset, endOffset, constructedIrType, "Cannot find delegated constructor call"
                )
            }

        // Unwrap substitution overrides from both derived class and a super class
        val constructorSymbol = referencedSymbol
            .unwrapCallRepresentative(referencedSymbol.containingClassLookupTag())
            .unwrapCallRepresentative((referencedSymbol.resolvedReturnType as? ConeClassLikeType)?.lookupTag)

        check(constructorSymbol is FirConstructorSymbol)

        val firDispatchReceiver = dispatchReceiver
        return convertWithOffsets { startOffset, endOffset ->
            val irConstructorSymbol = symbolTable.referenceConstructor(constructorSymbol)
            val typeArguments = constructedTypeRef.coneType.fullyExpandedType(session).typeArguments
            val constructor = constructorSymbol.fir
            /*
             * We should generate enum constructor call only if it is used to create new enum entry (so it's super constructor call)
             * If it is this constructor call that we are facing secondary constructor of enum, and should generate
             *   regular delegating constructor call
             *
             * enum class Some(val x: Int) {
             *   A(); // <---- super call, IrEnumConstructorCall
             *
             *   constructor() : this(10) // <---- this call, IrDelegatingConstructorCall
             * }
             */
            @OptIn(UnexpandedTypeCheck::class)
            if ((constructor.isFromEnumClass || constructor.returnTypeRef.isEnum) && this.isSuper) {
                IrEnumConstructorCallImpl(
                    startOffset, endOffset,
                    constructedIrType,
                    irConstructorSymbol,
                    typeArgumentsCount = constructor.typeParameters.size,
                    valueArgumentsCount = constructor.valueParameters.size
                )
            } else {
                IrDelegatingConstructorCallImpl(
                    startOffset, endOffset,
                    irBuiltIns.unitType,
                    irConstructorSymbol,
                    typeArgumentsCount = constructor.typeParameters.size,
                    // TODO: handle inner classes
                    valueArgumentsCount = constructor.valueParameters.size // irConstructorSymbol.owner.valueParameters.size
                )
            }.let {
                if (constructor.typeParameters.isNotEmpty()) {
                    if (typeArguments.isNotEmpty()) {
                        for ((index, typeArgument) in typeArguments.withIndex()) {
                            if (index >= constructor.typeParameters.size) break
                            val irType = (typeArgument as ConeKotlinTypeProjection).type.toIrType()
                            it.putTypeArgument(index, irType)
                        }
                    }
                }
                if (firDispatchReceiver !is FirNoReceiverExpression) {
                    it.dispatchReceiver = visitor.convertToIrExpression(firDispatchReceiver)
                }
                with(callGenerator) {
                    it.applyCallArguments(this@toIrDelegatingConstructorCall)
                }
            }
        }
    }

    private fun IrValueParameter.setDefaultValue(firValueParameter: FirValueParameter) {
        val firDefaultValue = firValueParameter.defaultValue
        if (firDefaultValue != null) {
            this.defaultValue = factory.createExpressionBody(visitor.convertToIrExpression(firDefaultValue))
        }
    }
}
