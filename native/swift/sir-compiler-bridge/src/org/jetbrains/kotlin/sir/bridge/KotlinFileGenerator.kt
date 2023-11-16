/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.sir.bridge

import java.io.File

class KotlinBridgesPrinter : BridgePrinter {

    private val imports = mutableSetOf<String>()

    private val functions = mutableListOf<List<String>>()

    override fun print(bridge: FunctionBridge) {
        functions += bridge.kotlinFunctionBridge.lines
        imports += bridge.kotlinFunctionBridge.packageDependencies
    }

    fun printToFile(file: File) {
        if (imports.isNotEmpty()) {
            imports.forEach {
                file.appendText("import $it")
            }
            file.appendText(lineSeparator)
        }
        functions.forEach { functionLines ->
            functionLines.forEach {
                file.appendText(it)
            }
            file.appendText(lineSeparator)
        }
    }

    companion object {
        private val lineSeparator: String = System.getProperty("line.separator")
    }
}