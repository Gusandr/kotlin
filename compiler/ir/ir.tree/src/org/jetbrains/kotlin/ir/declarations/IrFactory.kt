/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.declarations

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedContainerSource
import org.jetbrains.kotlin.types.Variance

interface IrFactory {
    val stageController: StageController

    fun createAnonymousInitializer(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        symbol: IrAnonymousInitializerSymbol,
        isStatic: Boolean = false,
    ): IrAnonymousInitializer

    fun createClass(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        name: Name,
        visibility: DescriptorVisibility,
        symbol: IrClassSymbol,
        kind: ClassKind,
        modality: Modality,
        isExternal: Boolean = false,
        isCompanion: Boolean = false,
        isInner: Boolean = false,
        isData: Boolean = false,
        isValue: Boolean = false,
        isExpect: Boolean = false,
        isFun: Boolean = false,
        source: SourceElement = SourceElement.NO_SOURCE,
    ): IrClass

    fun createConstructor(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        name: Name,
        visibility: DescriptorVisibility,
        isInline: Boolean,
        isExpect: Boolean,
        returnType: IrType,
        symbol: IrConstructorSymbol,
        isPrimary: Boolean,
        isExternal: Boolean,
        containerSource: DeserializedContainerSource? = null
    ): IrConstructor

    fun createEnumEntry(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        name: Name,
        symbol: IrEnumEntrySymbol,
    ): IrEnumEntry

    fun createErrorDeclaration(
        startOffset: Int,
        endOffset: Int,
        descriptor: DeclarationDescriptor? = null,
    ): IrErrorDeclaration

    fun createField(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        name: Name,
        visibility: DescriptorVisibility,
        symbol: IrFieldSymbol,
        type: IrType,
        isFinal: Boolean,
        isStatic: Boolean,
        isExternal: Boolean,
    ): IrField

    fun createSimpleFunction(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        name: Name,
        visibility: DescriptorVisibility,
        isInline: Boolean,
        isExpect: Boolean,
        returnType: IrType,
        modality: Modality,
        symbol: IrSimpleFunctionSymbol,
        isTailrec: Boolean,
        isSuspend: Boolean,
        isOperator: Boolean,
        isInfix: Boolean,
        isExternal: Boolean,
        containerSource: DeserializedContainerSource? = null,
        isFakeOverride: Boolean = origin == IrDeclarationOrigin.FAKE_OVERRIDE,
    ): IrSimpleFunction

    fun createFunctionWithLateBinding(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        name: Name,
        visibility: DescriptorVisibility,
        isInline: Boolean,
        isExpect: Boolean,
        returnType: IrType,
        modality: Modality,
        isTailrec: Boolean,
        isSuspend: Boolean,
        isOperator: Boolean,
        isInfix: Boolean,
        isExternal: Boolean = false,
        isFakeOverride: Boolean = origin == IrDeclarationOrigin.FAKE_OVERRIDE,
    ): IrFunctionWithLateBinding

    fun createLocalDelegatedProperty(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        name: Name,
        symbol: IrLocalDelegatedPropertySymbol,
        type: IrType,
        isVar: Boolean,
    ): IrLocalDelegatedProperty

    fun createProperty(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        name: Name,
        visibility: DescriptorVisibility,
        modality: Modality,
        symbol: IrPropertySymbol,
        isVar: Boolean,
        isConst: Boolean,
        isLateinit: Boolean,
        isDelegated: Boolean,
        isExternal: Boolean,
        containerSource: DeserializedContainerSource? = null,
        isExpect: Boolean = false,
        isFakeOverride: Boolean = origin == IrDeclarationOrigin.FAKE_OVERRIDE
    ): IrProperty

    fun createPropertyWithLateBinding(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        name: Name,
        visibility: DescriptorVisibility,
        modality: Modality,
        isVar: Boolean,
        isConst: Boolean,
        isLateinit: Boolean,
        isDelegated: Boolean,
        isExternal: Boolean = false,
        isExpect: Boolean = false,
        isFakeOverride: Boolean = origin == IrDeclarationOrigin.FAKE_OVERRIDE,
    ): IrPropertyWithLateBinding

    fun createTypeAlias(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        name: Name,
        visibility: DescriptorVisibility,
        symbol: IrTypeAliasSymbol,
        isActual: Boolean,
        expandedType: IrType,
    ): IrTypeAlias

    fun createTypeParameter(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        name: Name,
        symbol: IrTypeParameterSymbol,
        variance: Variance,
        index: Int,
        isReified: Boolean,
    ): IrTypeParameter

    fun createValueParameter(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        name: Name,
        type: IrType,
        isAssignable: Boolean,
        symbol: IrValueParameterSymbol,
        index: Int,
        varargElementType: IrType?,
        isCrossinline: Boolean,
        isNoinline: Boolean,
        isHidden: Boolean
    ): IrValueParameter

    // Bodies

    fun createExpressionBody(
        startOffset: Int,
        endOffset: Int,
        expression: IrExpression,
    ): IrExpressionBody

    fun createBlockBody(
        startOffset: Int,
        endOffset: Int,
    ): IrBlockBody


    @Deprecated(
        message = "This method was moved to an extension." +
                " This variant of the method will be removed when the 2024.2 IntelliJ platform is shipped (see KTIJ-26314).",
        replaceWith = ReplaceWith(
            """createBlockBody(
                startOffset = startOffset,
                endOffset = endOffset,
                initializer = initializer,
            )""",
            "org.jetbrains.kotlin.ir.declarations.createBlockBody",
        ),
        level = DeprecationLevel.HIDDEN,
    )
    fun createBlockBody(
        startOffset: Int,
        endOffset: Int,
        initializer: IrBlockBody.() -> Unit,
    ): IrBlockBody = createBlockBody(
        startOffset,
        endOffset,
        initializer,
    )

    @Deprecated(
        message = "This method was moved to an extension." +
                " This variant of the method will be removed when the 2024.2 IntelliJ platform is shipped (see KTIJ-26314).",
        replaceWith = ReplaceWith(
            """createBlockBody(
                startOffset = startOffset,
                endOffset = endOffset,
                statements = statements,
            )""",
            "org.jetbrains.kotlin.ir.declarations.createBlockBody",
        ),
        level = DeprecationLevel.HIDDEN,
    )
    fun createBlockBody(
        startOffset: Int,
        endOffset: Int,
        statements: List<IrStatement>,
    ): IrBlockBody = createBlockBody(
        startOffset,
        endOffset,
        statements,
    )

    @Deprecated(
        message = "The method's parameters were reordered." +
                " This variant of the method will be removed when the 2024.2 IntelliJ platform is shipped (see KTIJ-26314).",
        replaceWith = ReplaceWith(
            """createClass(
                startOffset = startOffset,
                endOffset = endOffset,
                origin = origin,
                name = name,
                visibility = visibility,
                symbol = symbol,
                kind = kind,
                modality = modality,
                isExternal = isExternal,
                isCompanion = isCompanion,
                isInner = isInner,
                isData = isData,
                isValue = isValue,
                isExpect = isExpect,
                isFun = isFun,
                source = source,
            )""",
        ),
        level = DeprecationLevel.HIDDEN,
    )
    fun createClass(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        symbol: IrClassSymbol,
        name: Name,
        kind: ClassKind,
        visibility: DescriptorVisibility,
        modality: Modality,
        isCompanion: Boolean = false,
        isInner: Boolean = false,
        isData: Boolean = false,
        isExternal: Boolean = false,
        isValue: Boolean = false,
        isExpect: Boolean = false,
        isFun: Boolean = false,
        source: SourceElement = SourceElement.NO_SOURCE,
    ): IrClass = createClass(
        startOffset,
        endOffset,
        origin,
        name,
        visibility,
        symbol,
        kind,
        modality,
        isExternal,
        isCompanion,
        isInner,
        isData,
        isValue,
        isExpect,
        isFun,
        source,
    )

    @Deprecated(
        message = "The method's parameters were reordered." +
                " This variant of the method will be removed when the 2024.2 IntelliJ platform is shipped (see KTIJ-26314).",
        replaceWith = ReplaceWith(
            """createConstructor(
                startOffset = startOffset,
                endOffset = endOffset,
                origin = origin,
                name = name,
                visibility = visibility,
                isInline = isInline,
                isExpect = isExpect,
                returnType = returnType,
                symbol = symbol,
                isPrimary = isPrimary,
                isExternal = isExternal,
                containerSource = containerSource,
            )""",
        ),
        level = DeprecationLevel.HIDDEN,
    )
    fun createConstructor(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        symbol: IrConstructorSymbol,
        name: Name,
        visibility: DescriptorVisibility,
        returnType: IrType,
        isInline: Boolean,
        isExternal: Boolean,
        isPrimary: Boolean,
        isExpect: Boolean,
        containerSource: DeserializedContainerSource? = null,
    ): IrConstructor = createConstructor(
        startOffset,
        endOffset,
        origin,
        name,
        visibility,
        isInline,
        isExpect,
        returnType,
        symbol,
        isPrimary,
        isExternal,
        containerSource,
    )

    @Deprecated(
        message = "The method's parameters were reordered." +
                " This variant of the method will be removed when the 2024.2 IntelliJ platform is shipped (see KTIJ-26314).",
        replaceWith = ReplaceWith(
            """createEnumEntry(
                startOffset = startOffset,
                endOffset = endOffset,
                origin = origin,
                name = name,
                symbol = symbol,
            )""",
        ),
        level = DeprecationLevel.HIDDEN,
    )
    fun createEnumEntry(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        symbol: IrEnumEntrySymbol,
        name: Name,
    ): IrEnumEntry = createEnumEntry(
        startOffset,
        endOffset,
        origin,
        name,
        symbol,
    )

    @Deprecated(
        message = "This method was moved to an extension." +
                " This variant of the method will be removed when the 2024.2 IntelliJ platform is shipped (see KTIJ-26314).",
        replaceWith = ReplaceWith(
            """createExpressionBody(
                expression = expression,
            )""",
            "org.jetbrains.kotlin.ir.declarations.createExpressionBody",
        ),
        level = DeprecationLevel.HIDDEN,
    )
    fun createExpressionBody(expression: IrExpression): IrExpressionBody =
        createExpressionBody(
            expression,
        )

    @Deprecated(
        message = "The method's parameters were reordered." +
                " This variant of the method will be removed when the 2024.2 IntelliJ platform is shipped (see KTIJ-26314).",
        replaceWith = ReplaceWith(
            """createField(
                startOffset = startOffset,
                endOffset = endOffset,
                origin = origin,
                name = name,
                visibility = visibility,
                symbol = symbol,
                type = type,
                isFinal = isFinal,
                isStatic = isStatic,
                isExternal = isExternal,
            )""",
        ),
        level = DeprecationLevel.HIDDEN,
    )
    fun createField(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        symbol: IrFieldSymbol,
        name: Name,
        type: IrType,
        visibility: DescriptorVisibility,
        isFinal: Boolean,
        isExternal: Boolean,
        isStatic: Boolean,
    ): IrField = createField(
        startOffset,
        endOffset,
        origin,
        name,
        visibility,
        symbol,
        type,
        isFinal,
        isStatic,
        isExternal,
    )

    @Deprecated(
        message = "The method's parameters were reordered." +
                " This variant of the method will be removed when the 2024.2 IntelliJ platform is shipped (see KTIJ-26314).",
        replaceWith = ReplaceWith(
            """createFunctionWithLateBinding(
                startOffset = startOffset,
                endOffset = endOffset,
                origin = origin,
                name = name,
                visibility = visibility,
                isInline = isInline,
                isExpect = isExpect,
                returnType = returnType,
                modality = modality,
                isTailrec = isTailrec,
                isSuspend = isSuspend,
                isOperator = isOperator,
                isInfix = isInfix,
                isExternal = isExternal,
            )""",
        ),
        level = DeprecationLevel.HIDDEN,
    )
    fun createFunctionWithLateBinding(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        name: Name,
        visibility: DescriptorVisibility,
        modality: Modality,
        returnType: IrType,
        isInline: Boolean,
        isExternal: Boolean,
        isTailrec: Boolean,
        isSuspend: Boolean,
        isOperator: Boolean,
        isInfix: Boolean,
        isExpect: Boolean,
    ): IrSimpleFunction = createFunctionWithLateBinding(
        startOffset,
        endOffset,
        origin,
        name,
        visibility,
        isInline,
        isExpect,
        returnType,
        modality,
        isTailrec,
        isSuspend,
        isOperator,
        isInfix,
        isExternal,
    )

    @Deprecated(
        message = "The method's parameters were reordered." +
                " This variant of the method will be removed when the 2024.2 IntelliJ platform is shipped (see KTIJ-26314).",
        replaceWith = ReplaceWith(
            """createLocalDelegatedProperty(
                startOffset = startOffset,
                endOffset = endOffset,
                origin = origin,
                name = name,
                symbol = symbol,
                type = type,
                isVar = isVar,
            )""",
        ),
        level = DeprecationLevel.HIDDEN,
    )
    fun createLocalDelegatedProperty(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        symbol: IrLocalDelegatedPropertySymbol,
        name: Name,
        type: IrType,
        isVar: Boolean,
    ): IrLocalDelegatedProperty = createLocalDelegatedProperty(
        startOffset,
        endOffset,
        origin,
        name,
        symbol,
        type,
        isVar,
    )

    @Deprecated(
        message = "The method's parameters were reordered." +
                " This variant of the method will be removed when the 2024.2 IntelliJ platform is shipped (see KTIJ-26314).",
        replaceWith = ReplaceWith(
            """createProperty(
                startOffset = startOffset,
                endOffset = endOffset,
                origin = origin,
                name = name,
                visibility = visibility,
                modality = modality,
                symbol = symbol,
                isVar = isVar,
                isConst = isConst,
                isLateinit = isLateinit,
                isDelegated = isDelegated,
                isExternal = isExternal,
                containerSource = containerSource,
                isExpect = isExpect,
                isFakeOverride = isFakeOverride,
            )""",
        ),
        level = DeprecationLevel.HIDDEN,
    )
    fun createProperty(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        symbol: IrPropertySymbol,
        name: Name,
        visibility: DescriptorVisibility,
        modality: Modality,
        isVar: Boolean,
        isConst: Boolean,
        isLateinit: Boolean,
        isDelegated: Boolean,
        isExternal: Boolean,
        isExpect: Boolean = false,
        isFakeOverride: Boolean = origin == IrDeclarationOrigin.FAKE_OVERRIDE,
        containerSource: DeserializedContainerSource? = null,
    ): IrProperty = createProperty(
        startOffset,
        endOffset,
        origin,
        name,
        visibility,
        modality,
        symbol,
        isVar,
        isConst,
        isLateinit,
        isDelegated,
        isExternal,
        containerSource,
        isExpect,
        isFakeOverride,
    )

    @Deprecated(
        message = "The method has been renamed, and its parameters were reordered." +
                " This variant of the method will be removed when the 2024.2 IntelliJ platform is shipped (see KTIJ-26314).",
        replaceWith = ReplaceWith(
            """createSimpleFunction(
                startOffset = startOffset,
                endOffset = endOffset,
                origin = origin,
                name = name,
                visibility = visibility,
                isInline = isInline,
                isExpect = isExpect,
                returnType = returnType,
                modality = modality,
                symbol = symbol,
                isTailrec = isTailrec,
                isSuspend = isSuspend,
                isOperator = isOperator,
                isInfix = isInfix,
                isExternal = isExternal,
                containerSource = containerSource,
                isFakeOverride = isFakeOverride,
            )""",
        ),
        level = DeprecationLevel.HIDDEN,
    )
    fun createFunction(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        symbol: IrSimpleFunctionSymbol,
        name: Name,
        visibility: DescriptorVisibility,
        modality: Modality,
        returnType: IrType,
        isInline: Boolean,
        isExternal: Boolean,
        isTailrec: Boolean,
        isSuspend: Boolean,
        isOperator: Boolean,
        isInfix: Boolean,
        isExpect: Boolean,
        isFakeOverride: Boolean = origin == IrDeclarationOrigin.FAKE_OVERRIDE,
        containerSource: DeserializedContainerSource? = null,
    ): IrSimpleFunction = createSimpleFunction(
        startOffset,
        endOffset,
        origin,
        name,
        visibility,
        isInline,
        isExpect,
        returnType,
        modality,
        symbol,
        isTailrec,
        isSuspend,
        isOperator,
        isInfix,
        isExternal,
        containerSource,
        isFakeOverride,
    )

    @Deprecated(
        message = "The method's parameters were reordered." +
                " This variant of the method will be removed when the 2024.2 IntelliJ platform is shipped (see KTIJ-26314).",
        replaceWith = ReplaceWith(
            """createTypeAlias(
                startOffset = startOffset,
                endOffset = endOffset,
                origin = origin,
                name = name,
                visibility = visibility,
                symbol = symbol,
                isActual = isActual,
                expandedType = expandedType,
            )""",
        ),
        level = DeprecationLevel.HIDDEN,
    )
    fun createTypeAlias(
        startOffset: Int,
        endOffset: Int,
        symbol: IrTypeAliasSymbol,
        name: Name,
        visibility: DescriptorVisibility,
        expandedType: IrType,
        isActual: Boolean,
        origin: IrDeclarationOrigin,
    ): IrTypeAlias = createTypeAlias(
        startOffset,
        endOffset,
        origin,
        name,
        visibility,
        symbol,
        isActual,
        expandedType,
    )

    @Deprecated(
        message = "The method's parameters were reordered." +
                " This variant of the method will be removed when the 2024.2 IntelliJ platform is shipped (see KTIJ-26314).",
        replaceWith = ReplaceWith(
            """createTypeParameter(
                startOffset = startOffset,
                endOffset = endOffset,
                origin = origin,
                name = name,
                symbol = symbol,
                variance = variance,
                index = index,
                isReified = isReified,
            )""",
        ),
        level = DeprecationLevel.HIDDEN,
    )
    fun createTypeParameter(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        symbol: IrTypeParameterSymbol,
        name: Name,
        index: Int,
        isReified: Boolean,
        variance: Variance,
    ): IrTypeParameter = createTypeParameter(
        startOffset,
        endOffset,
        origin,
        name,
        symbol,
        variance,
        index,
        isReified,
    )

    @Deprecated(
        message = "The method's parameters were reordered." +
                " This variant of the method will be removed when the 2024.2 IntelliJ platform is shipped (see KTIJ-26314).",
        replaceWith = ReplaceWith(
            """createValueParameter(
                startOffset = startOffset,
                endOffset = endOffset,
                origin = origin,
                name = name,
                type = type,
                isAssignable = isAssignable,
                symbol = symbol,
                index = index,
                varargElementType = varargElementType,
                isCrossinline = isCrossinline,
                isNoinline = isNoinline,
                isHidden = isHidden,
            )""",
        ),
        level = DeprecationLevel.HIDDEN,
    )
    fun createValueParameter(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        symbol: IrValueParameterSymbol,
        name: Name,
        index: Int,
        type: IrType,
        varargElementType: IrType?,
        isCrossinline: Boolean,
        isNoinline: Boolean,
        isHidden: Boolean,
        isAssignable: Boolean,
    ): IrValueParameter = createValueParameter(
        startOffset,
        endOffset,
        origin,
        name,
        type,
        isAssignable,
        symbol,
        index,
        varargElementType,
        isCrossinline,
        isNoinline,
        isHidden,
    )
}
