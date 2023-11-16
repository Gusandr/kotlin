/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.sir.analysisapi

import org.jetbrains.kotlin.analysis.api.KtAnalysisSession
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.isPublic
import org.jetbrains.kotlin.sir.SirForeignFunction
import org.jetbrains.kotlin.sir.SirModule

/**
 * A root interface for classes that produce Swift IR elements.
 */
interface SirFactory {
    val moduleToFill: SirModule
    fun build(fromFile: KtElement)
}

class SirGenerator(override val moduleToFill: SirModule) : SirFactory {
    override fun build(fromFile: KtElement){
        val res = mutableListOf<SirForeignFunction>()
        fromFile.accept(
            object : KtTreeVisitorVoid() {
                override fun visitTypeArgumentList(typeArgumentList: KtTypeArgumentList) {
                    super.visitTypeArgumentList(typeArgumentList)
                }

                override fun visitValueArgumentList(list: KtValueArgumentList) {
                    super.visitValueArgumentList(list)
                }

                override fun visitParameter(parameter: KtParameter) {
                    super.visitParameter(parameter)
                }

                override fun visitParameterList(list: KtParameterList) {
                    super.visitParameterList(list)
                }

                override fun visitNamedFunction(function: KtNamedFunction) {
                    super.visitNamedFunction(function)
                    function
                        .takeIf { function.isPublic }
                        ?.fqName
                        ?.pathSegments()
                        ?.toListString()
                        ?.let { names -> SirForeignFunction(fqName = names) }
                        ?.let { res.add(it) }
                }
            }
        )
        res.forEach(moduleToFill.declarations::add)
    }

    private fun List<Name>.toListString() = map { it.asString() }
}
