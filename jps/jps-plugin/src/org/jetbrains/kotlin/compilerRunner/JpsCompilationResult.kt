/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.compilerRunner

import org.jetbrains.kotlin.build.report.metrics.BuildMetrics
import org.jetbrains.kotlin.build.report.metrics.BuildMetricsReporterImpl
import org.jetbrains.kotlin.build.report.metrics.JpsBuildPerformanceMetric
import org.jetbrains.kotlin.build.report.metrics.JpsBuildTime
import org.jetbrains.kotlin.daemon.common.*
import org.jetbrains.kotlin.jps.build.KotlinBuilder
import java.io.Serializable
import java.rmi.RemoteException
import java.rmi.server.UnicastRemoteObject

class JpsCompilationResult : CompilationResults,
    UnicastRemoteObject(
        SOCKET_ANY_FREE_PORT,
        LoopbackNetworkInterface.clientLoopbackSocketFactory,
        LoopbackNetworkInterface.serverLoopbackSocketFactory
    ) {

    var icLogLines: List<String> = emptyList()
    val compiledFiles = ArrayList<String>()

    private val buildMetricsReporter = BuildMetricsReporterImpl<JpsBuildTime, JpsBuildPerformanceMetric>()
    val buildMetrics: BuildMetrics<JpsBuildTime, JpsBuildPerformanceMetric>
        get() = buildMetricsReporter.getMetrics()
    private val log = JpsKotlinLogger(KotlinBuilder.LOG)
    @Throws(RemoteException::class)
    override fun add(compilationResultCategory: Int, value: Serializable) {
        System.out.println("DEBUG: $compilationResultCategory category $value")
        log.info("DEBUG: $compilationResultCategory category $value")
        when (compilationResultCategory) {
            CompilationResultCategory.IC_COMPILE_ITERATION.code -> {
                @Suppress("UNCHECKED_CAST")
                val compileIterationResult = value as? CompileIterationResult
                if (compileIterationResult != null) {
                    val sourceFiles = compileIterationResult.sourceFiles
                    buildMetrics.buildPerformanceMetrics.add(JpsBuildPerformanceMetric.IC_COMPILE_ITERATION)
                    compiledFiles.addAll(sourceFiles.map { it.path })
                }
            }
            CompilationResultCategory.BUILD_REPORT_LINES.code,
            CompilationResultCategory.VERBOSE_BUILD_REPORT_LINES.code -> {
                @Suppress("UNCHECKED_CAST")
                (value as? List<String>)?.let { icLogLines = it }
            }
            CompilationResultCategory.BUILD_METRICS.code -> {
               //GradleMetrics are used, ignore for now
            }
        }
    }
}