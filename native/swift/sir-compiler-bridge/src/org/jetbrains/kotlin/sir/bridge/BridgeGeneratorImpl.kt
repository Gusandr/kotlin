/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.sir.bridge

internal class BridgeGeneratorImpl : BridgeGenerator {
    override fun generate(request: BridgeRequest): FunctionBridge {

        val (kotlinReturnType, cReturnType) = bridgeType(request.returnType)
        val parameterBridges = request.parameters.map { bridgeParameter(it) }

        val cDeclaration = createCDeclaration(request.bridgeName, cReturnType, parameterBridges.map { it.c })
        val kotlinBridge = createKotlinBridge(request.bridgeName, request.fqName, kotlinReturnType, parameterBridges.map { it.kotlin })
        return FunctionBridge(
            KotlinFunctionBridge(kotlinBridge, emptyList()),
            CFunctionBridge(cDeclaration, emptyList())
        )
    }
}

private fun createKotlinBridge(bridgeName: String, functionFqName: List<String>, returnType: KotlinType, parameterBridges: List<KotlinBridgeParameter>): List<String> {
    val declaration = createKotlinDeclarationSignature(bridgeName, returnType, parameterBridges)
    val resultName = "result"
    val callSite = createCallSite(functionFqName, parameterBridges.map { it.name }, resultName)
    return """
        $declaration {
            $callSite
            return $resultName
        }
    """.trimIndent().lines()
}

private fun createCallSite(functionFqName: List<String>, parameterNames: List<String>, resultName: String): String {
    val functionCall = "${functionFqName.joinToString(separator = ".")}(${parameterNames.joinToString(",")})"
    return "val $resultName = $functionCall"
}

private fun createKotlinDeclarationSignature(bridgeName: String, returnType: KotlinType, parameters: List<KotlinBridgeParameter>): String {
    return "public fun $bridgeName(${
        parameters.joinToString(
            separator = ", ",
            transform = KotlinBridgeParameter::codeSnippet
        )
    }): ${returnType.repr}"
}

private fun createCDeclaration(bridgeName: String, returnType: CType, parameters: List<CBridgeParameter>): List<String> {
    val declaration =
        "${returnType.repr} $bridgeName(${parameters.joinToString(separator = ", ", transform = CBridgeParameter::codeSnippet)});"
    return listOf(declaration)
}


private fun bridgeType(type: BridgeRequest.Type): Pair<KotlinType, CType> {
    return when (type) {
        BridgeRequest.Type.Boolean -> (KotlinType.Boolean to CType.Bool)
        BridgeRequest.Type.Int -> (KotlinType.Int to CType.Int32)
    }
}

private fun bridgeParameter(request: BridgeRequest.Parameter): BridgeParameter {
    val cName = createCParameterName(request.name)
    val kotlinName = createKotlinParameterName(request.name)
    val (kotlinType, cType) = when (request.type) {
        BridgeRequest.Type.Boolean -> (KotlinType.Boolean to CType.Bool)
        BridgeRequest.Type.Int -> (KotlinType.Int to CType.Int32)
    }
    return BridgeParameter(
        KotlinBridgeParameter(kotlinName, kotlinType),
        CBridgeParameter(cName, cType)
    )
}

fun createKotlinParameterName(name: String): String {
    return "${name}_wrapped"
}

fun createCParameterName(kotlinName: String): String {
    // TODO: Post-process
    return kotlinName
}

internal data class BridgeParameter(
    val kotlin: KotlinBridgeParameter,
    val c: CBridgeParameter,
)

internal data class CBridgeParameter(
    val name: String,
    val type: CType,
) {
    fun codeSnippet(): String =
        "${type.repr} $name"
}

enum class CType(val repr: String) {
    Int32("int32_t"),
    Bool("_Boolean"),
}

internal data class KotlinBridgeParameter(
    val name: String,
    val type: KotlinType,
) {
    fun codeSnippet(): String =
        "$name: ${type.repr}"
}

enum class KotlinType(val repr: String) {
    Int("Int"),
    Boolean("Boolean")
}

