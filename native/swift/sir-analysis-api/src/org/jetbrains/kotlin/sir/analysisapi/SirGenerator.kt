/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.sir.analysisapi

import org.jetbrains.kotlin.analysis.api.KtAnalysisSession
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.sir.SirElement

/**
 * A root interface for classes that produce Swift IR elements.
 */
interface SirFactory {
    fun build(fromFile: KtFile): SirElement
}

context(KtAnalysisSession)
class SirGenerator : SirFactory {
    override fun build(fromFile: KtFile): SirElement {
        val res = mutableListOf<CallableId>()
        fromFile.accept(
            object : KtTreeVisitorVoid() {
                override fun visitNamedFunction(function: KtNamedFunction) {
                    super.visitNamedFunction(function)
                    val functionSymbol = function.getFunctionLikeSymbol().callableIdIfNonLocal ?: return
                    res.add(functionSymbol)
                }
            }
        )
        @Suppress("DEPRECATION")
        val topLevelFunctions = ArrayOfFunctions(res.toList())

        return topLevelFunctions
    }
}

@Deprecated("This should not be merged into master. That is just a mock while there is no SirElement Implementation.")
internal data class ArrayOfFunctions(val functions: List<CallableId>) : SirElement
