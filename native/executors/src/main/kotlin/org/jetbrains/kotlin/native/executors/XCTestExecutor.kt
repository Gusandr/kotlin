/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.native.executors

import org.jetbrains.kotlin.konan.target.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * Abstract class representing an executor for XCTest tests.
 *
 * This class is a composable executor that relies on the underlying [Executor].
 *
 * @property configurables The Apple target to be run on.
 * @property executor The underlying executor to execute target specific operations.
 */
abstract class AbstractXCTestExecutor(
    private val configurables: AppleConfigurables,
    private val executor: Executor
) : Executor {
    private val hostExecutor = HostExecutor()

    private val target by configurables::target

    companion object {
        fun availableFor(configurables: AppleConfigurables): Boolean =
            HostManager.host is KonanTarget.MACOS_ARM64 || configurables.target is KonanTarget.MACOS_X64

        fun supportedTargets(): List<KonanTarget> = when (HostManager.host) {
            KonanTarget.MACOS_X64 -> listOf(KonanTarget.MACOS_X64, KonanTarget.IOS_X64)
            KonanTarget.MACOS_ARM64 -> listOf(KonanTarget.MACOS_ARM64, KonanTarget.IOS_SIMULATOR_ARM64)
            else -> error("${HostManager.host} isn't supported by XCTestExecutor")
        }
    }

    init {
        require(availableFor(configurables)) {
            "$this executor isn't available for $configurables"
        }

        require(configurables.target in supportedTargets()) {
            "$this executor is unable to run ${configurables.target}"
        }
    }

    private fun targetPlatform(): String {
        val xcodeTarget = when (target) {
            KonanTarget.MACOS_X64, KonanTarget.MACOS_ARM64 -> "macosx"
            KonanTarget.IOS_X64, KonanTarget.IOS_SIMULATOR_ARM64 -> "iphonesimulator"
            KonanTarget.IOS_ARM64 -> "iphoneos"
            else -> error("Target $target is not supported buy the executor")
        }

        val stdout = ByteArrayOutputStream()
        val request = ExecuteRequest(
            "/usr/bin/xcrun",
            args = mutableListOf("--sdk", xcodeTarget, "--show-sdk-platform-path"),
            stdout = stdout
        )
        hostExecutor.execute(request).assertSuccess()

        return stdout.toString("UTF-8").trim()
    }

    private val frameworkPath: String
        get() = "${targetPlatform()}/Developer/Library/Frameworks/"

    private val xcTestExecutablePath: String
        get() = "${targetPlatform()}/Developer/Library/Xcode/Agents/xctest"

    override fun execute(request: ExecuteRequest): ExecuteResponse {
        val originalBundle = File(request.executableAbsolutePath)
        val bundleToExecute = if (request.args.isNotEmpty()) {
            // Copy the bundle to a temp dir
            val dir = Files.createTempDirectory("tmp-xctest-runner")
            val newBundleFile = originalBundle.run {
                val newPath = dir.resolve(name)
                copyRecursively(newPath.toFile())
                newPath.toFile()
            }
            check(newBundleFile.exists())

            // Passing arguments to the XCTest-runner using Info.plist file.
            val infoPlist = newBundleFile.walk()
                .firstOrNull { it.name == "Info.plist" }
                ?.absolutePath
            checkNotNull(infoPlist) { "Info.plist of xctest-bundle wasn't found. Check the bundle contents and location "}

            val writeArgsRequest = ExecuteRequest(
                executableAbsolutePath = "/usr/libexec/PlistBuddy",
                args = mutableListOf("-c", "Add :KotlinNativeTestArgs string ${request.args.joinToString(" ")}", infoPlist)
            )
            val writeResponse = hostExecutor.execute(writeArgsRequest)
            writeResponse.assertSuccess()

            newBundleFile
        } else {
            originalBundle
        }

        val response = executor.execute(request.copying {
            environment["DYLD_FRAMEWORK_PATH"] = frameworkPath
            executableAbsolutePath = xcTestExecutablePath
            args.clear()
            args.add(bundleToExecute.absolutePath)
        })

        if (bundleToExecute != originalBundle) {
            bundleToExecute.apply {
                // Remove the copied bundle after the run
                deleteRecursively()
                // Also remove the temp directory that contained this bundle
                parentFile.delete()
            }
        }
        return response
    }
}

/**
 * XCTest executor that runs tests on a host machine.
 * It extends [AbstractXCTestExecutor] and uses [HostExecutor] to handle host-specific execution.
 *
 * @param configurables a test execution target.
 */
class XCTestHostExecutor(configurables: AppleConfigurables) : AbstractXCTestExecutor(configurables, HostExecutor())

/**
 * XCTest executor that runs tests on simulators on a host machine.
 * It extends [AbstractXCTestExecutor] and uses [XcodeSimulatorExecutor] to handle simulator-specific operations.
 *
 * @param configurables a test execution target.
 */
class XCTestSimulatorExecutor(configurables: AppleConfigurables) :
    AbstractXCTestExecutor(configurables, XcodeSimulatorExecutor(configurables))