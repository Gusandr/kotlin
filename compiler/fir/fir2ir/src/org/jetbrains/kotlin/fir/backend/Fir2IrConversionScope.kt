/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.backend

import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.util.isSetter
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.util.PrivateForInline

@OptIn(PrivateForInline::class)
class Fir2IrConversionScope(val components: Fir2IrComponents) {
    @PublishedApi
    @PrivateForInline
    internal val parentStack = mutableListOf<IrDeclarationParent>()

    @PublishedApi
    @PrivateForInline
    internal val containingFirClassStack = mutableListOf<FirClass>()

    @PublishedApi
    @PrivateForInline
    internal val currentlyGeneratedDelegatedConstructors = mutableMapOf<IrClass, IrConstructor>()

    inline fun <T : IrDeclarationParent, R> withParent(parent: T, f: T.() -> R): R {
        parentStack += parent
        try {
            return parent.f()
        } finally {
            parentStack.removeAt(parentStack.size - 1)
        }
    }

    internal fun <T> forDelegatingConstructorCall(constructor: IrConstructor, irClass: IrClass, f: () -> T): T {
        currentlyGeneratedDelegatedConstructors[irClass] = constructor
        try {
            return f()
        } finally {
            currentlyGeneratedDelegatedConstructors.remove(irClass)
        }
    }

    fun getConstructorForCurrentlyGeneratedDelegatedConstructor(itClass: IrClass): IrConstructor? =
        currentlyGeneratedDelegatedConstructors[itClass]

    fun containingFileIfAny(): IrFile? = parentStack.getOrNull(0) as? IrFile

    inline fun withContainingFirClass(containingFirClass: FirClass, f: () -> Unit) {
        containingFirClassStack += containingFirClass
        try {
            f()
        } finally {
            containingFirClassStack.removeAt(containingFirClassStack.size - 1)
        }
    }

    fun parentFromStack(): IrDeclarationParent = parentStack.last()

    fun parentAccessorOfPropertyFromStack(property: IrProperty): IrSimpleFunction? {
        for (parent in parentStack.asReversed()) {
            when (parent) {
                property.getter -> return property.getter
                property.setter -> return property.setter
            }
        }
        return null
    }

    fun <T : IrDeclaration> applyParentFromStackTo(declaration: T): T {
        declaration.parent = parentStack.last()
        return declaration
    }

    fun containerFirClass(): FirClass? = containingFirClassStack.lastOrNull()

    @PublishedApi
    @PrivateForInline
    internal val functionStack = mutableListOf<IrFunction>()

    inline fun <T : IrFunction, R> withFunction(function: T, f: T.() -> R): R {
        functionStack += function
        try {
            return function.f()
        } finally {
            functionStack.removeAt(functionStack.size - 1)
        }
    }

    @PublishedApi
    @PrivateForInline
    internal val propertyStack = mutableListOf<Pair<IrProperty, FirProperty?>>()

    inline fun <R> withProperty(property: IrProperty, firProperty: FirProperty? = null, f: IrProperty.() -> R): R {
        propertyStack += (property to firProperty)
        try {
            return property.f()
        } finally {
            propertyStack.removeAt(propertyStack.size - 1)
        }
    }

    @PublishedApi
    @PrivateForInline
    internal val classStack = mutableListOf<IrClass>()

    inline fun <R> withClass(klass: IrClass, f: IrClass.() -> R): R {
        classStack += klass
        return try {
            klass.f()
        } finally {
            classStack.removeAt(classStack.size - 1)
        }
    }

    @PublishedApi
    @PrivateForInline
    internal val whenSubjectVariableStack = mutableListOf<IrVariable>()

    @PublishedApi
    @PrivateForInline
    internal val safeCallSubjectVariableStack = mutableListOf<IrVariable>()

    inline fun <T> withWhenSubject(subject: IrVariable?, f: () -> T): T {
        if (subject != null) whenSubjectVariableStack += subject
        try {
            return f()
        } finally {
            if (subject != null) whenSubjectVariableStack.removeAt(whenSubjectVariableStack.size - 1)
        }
    }

    inline fun <T> withSafeCallSubject(subject: IrVariable?, f: () -> T): T {
        if (subject != null) safeCallSubjectVariableStack += subject
        try {
            return f()
        } finally {
            if (subject != null) safeCallSubjectVariableStack.removeAt(safeCallSubjectVariableStack.size - 1)
        }
    }

    fun returnTarget(expression: FirReturnExpression): IrFunction {
        val irTarget = when (val firTarget = expression.target.labeledElement) {
            is FirConstructor -> {
                val signature = components.signatureComposer.composeSignature(firTarget)
                components.symbolTable.referenceConstructor(firTarget.symbol, signature).owner
            }
            is FirPropertyAccessor -> {
                var answer: IrFunction? = null
                for ((property, firProperty) in propertyStack.asReversed()) {
                    if (firProperty?.getter === firTarget) {
                        answer = property.getter
                    } else if (firProperty?.setter === firTarget) {
                        answer = property.setter
                    }
                }
                answer
            }
            else -> {
                val signature = components.signatureComposer.composeSignature(firTarget)
                components.symbolTable.referenceFunction(firTarget.symbol, signature).owner
            }
        }
        for (potentialTarget in functionStack.asReversed()) {
            if (potentialTarget == irTarget) {
                return potentialTarget
            }
        }
        return functionStack.last()
    }

    fun parent(): IrDeclarationParent? = parentStack.lastOrNull()

    fun defaultConversionTypeOrigin(): ConversionTypeOrigin =
        if ((parent() as? IrFunction)?.isSetter == true) ConversionTypeOrigin.SETTER else ConversionTypeOrigin.DEFAULT

    // contract: returns not null => irClassSymbol is bound
    fun dispatchReceiverParameter(irClassSymbol: IrClassSymbol): IrValueParameter? {
        // If class symbol is not bound then this class came from dependencies and can not appear in coversion scope
        if (!irClassSymbol.isBound) return null
        val irClass = irClassSymbol.owner
        for (function in functionStack.asReversed()) {
            if (function.parentClassOrNull == irClass) {
                // An inner class's constructor needs an instance of the outer class as a dispatch receiver.
                // However, if we are converting `this` receiver inside that constructor, now we should point to the inner class instance.
                if (function is IrConstructor && irClass.isInner) {
                    irClass.thisReceiver?.let { return it }
                }
                function.dispatchReceiverParameter?.let { return it }
            }
        }
        return irClass.thisReceiver
    }

    fun lastClass(): IrClass? = classStack.lastOrNull()

    fun lastWhenSubject(): IrVariable = whenSubjectVariableStack.last()
    fun lastSafeCallSubject(): IrVariable = safeCallSubjectVariableStack.last()
}
