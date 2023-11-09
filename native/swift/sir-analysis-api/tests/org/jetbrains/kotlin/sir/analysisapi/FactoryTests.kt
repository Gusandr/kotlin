/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.sir.analysisapi

import org.junit.jupiter.api.Test

class FactoryTests : AbstractKotlinSirContextTest() {
    @Test
    fun simple_function() = runTest("native/swift/sir-analysis-api/testData/simple_function/simple_function.kt")

    @Test
    fun namespaced_function() = runTest("native/swift/sir-analysis-api/testData/namespaced_functions/namespaced_functions.kt")
}
