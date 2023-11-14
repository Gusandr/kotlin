/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.linkage.partial

import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.linkage.partial.ExploredClassifier
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrTypeAbbreviation
import org.jetbrains.kotlin.ir.types.IrTypeArgument
import org.jetbrains.kotlin.ir.types.SimpleTypeNullability

/**
 * Replacement for IR types that reference unusable classifier symbols.
 * Behaves like [kotlin.Any]?. Preserves [ExploredClassifier.Unusable].
 */
internal class PartiallyLinkedMarkerType(
    builtIns: IrBuiltIns,
    val unusableClassifier: ExploredClassifier.Unusable
) : IrSimpleType(null) {
    override val annotations get() = emptyList<IrConstructorCall>()
    override val classifier = builtIns.anyClass
    override val nullability get() = SimpleTypeNullability.MARKED_NULLABLE
    override val arguments get() = emptyList<IrTypeArgument>()
    override val abbreviation: IrTypeAbbreviation? get() = null

    override fun equals(other: Any?) = (other as? PartiallyLinkedMarkerType)?.unusableClassifier == unusableClassifier
    override fun hashCode() = unusableClassifier.hashCode()
}
