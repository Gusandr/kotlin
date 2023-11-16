/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.sir.bridge

import java.io.File

class CBridgesPrinter : BridgePrinter {

    private val includes = mutableSetOf<String>()

    private val functions = mutableListOf<List<String>>()

    override fun print(bridge: FunctionBridge) {
        functions += bridge.cDeclarationBridge.lines
        includes += bridge.cDeclarationBridge.headerDependencies
    }

    fun printToFile(file: File) {
        if (includes.isNotEmpty()) {
            includes.forEach {
                file.appendText("#include <$it>")
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