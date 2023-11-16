/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.sir.bridge

import org.jetbrains.kotlin.test.services.JUnit5Assertions.assertEqualsToFile
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class Tests {
    @Test
    fun smoke() {
        val request = BridgeRequest(
            fqName = listOf("a", "b", "foo"),
            bridgeName = "a_b_foo_bridge",
            parameters = listOf(BridgeRequest.Parameter("param", BridgeRequest.Type.Int)),
            returnType = BridgeRequest.Type.Int
        )
        val generator: BridgeGenerator = BridgeGeneratorImpl()
        val kotlinBridgesPrinter = KotlinBridgesPrinter()
        val cBridgesPrinter = CBridgesPrinter()

        val bridge = generator.generate(request)
        kotlinBridgesPrinter.print(bridge)
        cBridgesPrinter.print(bridge)


        val testDir = createTempDirectory(Tests::class.java.canonicalName).toFile()
        val actualKt = testDir.resolve("actual.kt").also {
            it.createNewFile()
        }
        val actualHeader = testDir.resolve("actual.h").also {
            it.createNewFile()
        }
        kotlinBridgesPrinter.printToFile(actualKt)
        cBridgesPrinter.printToFile(actualHeader)
        val expectHeader = File("/Users/Sergey.Bogolepov/IdeaProjects/kotlin/native/swift/sir-compiler-bridge/testData/smoke0/expected.h")
        assertEqualsToFile(expectHeader, actualHeader.readText())
    }
}