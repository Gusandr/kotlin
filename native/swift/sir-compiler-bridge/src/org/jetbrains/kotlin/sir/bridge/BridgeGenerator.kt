/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.sir.bridge

/**
 * Description of a Kotlin function for which we are creating the bridge.
 *
 * @param fqName Fully qualified name of the function we are bridging.
 * @param bridgeName C name of the bridge
 */
class BridgeRequest(
    val fqName: List<String>,
    val bridgeName: String,
    val parameters: List<Parameter>,
    val returnType: Type,
) {
    class Parameter(
        val name: String,
        val type: Type,
    )

    sealed interface Type {
        object Int : Type
        object Boolean : Type
    }
}

/**
 * C declaration of the bridge and Kotlin definition of it.
 */
class FunctionBridge(
    val kotlinFunctionBridge: KotlinFunctionBridge,
    val cDeclarationBridge: CFunctionBridge,
)

class CFunctionBridge(
    val lines: List<String>,
    val headerDependencies: List<String>,
)

class KotlinFunctionBridge(
    val lines: List<String>,
    val packageDependencies: List<String>,
)

/**
 * Generates [FunctionBridge] that binds SIR wrapper to its Kotlin origin.
 */
interface BridgeGenerator {
    fun generate(request: BridgeRequest): FunctionBridge
}

/**
 * Represents a bridge printer.
 */
interface BridgePrinter {
    fun print(bridge: FunctionBridge)
}