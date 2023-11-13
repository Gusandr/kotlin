/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.low.level.api.fir.file.structure

import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.low.level.api.fir.test.configurators.AnalysisApiFirOutOfContentRootTestConfigurator
import org.jetbrains.kotlin.analysis.low.level.api.fir.test.configurators.AnalysisApiFirScriptTestConfigurator
import org.jetbrains.kotlin.analysis.low.level.api.fir.test.configurators.AnalysisApiFirSourceTestConfigurator
import org.jetbrains.kotlin.analysis.test.framework.services.expressionMarkerProvider
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.test.services.TestModuleStructure
import org.jetbrains.kotlin.test.services.TestServices

abstract class AbstractDanglingFileInBlockModificationTes : AbstractInBlockModificationTest() {
    override fun doTestByFileStructure(ktFile: KtFile, moduleStructure: TestModuleStructure, testServices: TestServices) {
        val selectedElement = testServices.expressionMarkerProvider
            .getSelectedElementOfTypeByDirective(ktFile, moduleStructure.modules.single())

        val ktPsiFactory = KtPsiFactory.contextual(ktFile, markGenerated = true, eventSystemEnabled = true)
        val fakeKtFile = ktPsiFactory.createFile(ktFile.name, ktFile.text)
        val fakeSelectedElement = PsiTreeUtil.findSameElementInCopy(selectedElement, fakeKtFile)

        doTest(fakeKtFile, fakeSelectedElement, moduleStructure, testServices)
    }
}

abstract class AbstractSourceDanglingFileInBlockModificationTest : AbstractDanglingFileInBlockModificationTes() {
    override val configurator = AnalysisApiFirSourceTestConfigurator(analyseInDependentSession = false)
}

abstract class AbstractOutOfContentRootDanglingFileInBlockModificationTest : AbstractDanglingFileInBlockModificationTes() {
    override val configurator get() = AnalysisApiFirOutOfContentRootTestConfigurator
}

abstract class AbstractScriptDanglingFileInBlockModificationTest : AbstractDanglingFileInBlockModificationTes() {
    override val configurator = AnalysisApiFirScriptTestConfigurator(analyseInDependentSession = false)
}
