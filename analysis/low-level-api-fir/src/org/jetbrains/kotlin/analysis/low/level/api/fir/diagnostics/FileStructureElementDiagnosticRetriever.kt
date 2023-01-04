/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.low.level.api.fir.diagnostics

import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.analysis.low.level.api.fir.LLFirModuleResolveComponents
import org.jetbrains.kotlin.analysis.low.level.api.fir.diagnostics.fir.PersistenceContextCollector
import org.jetbrains.kotlin.analysis.low.level.api.fir.diagnostics.fir.PersistentCheckerContextFactory
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.collectors.DiagnosticCollectorComponents
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.resolve.SessionHolderImpl
import org.jetbrains.kotlin.util.withSourceCodeAnalysisExceptionUnwrapping

internal abstract class FileStructureElementDiagnosticRetriever {
    abstract fun retrieve(
        firFile: FirFile,
        collector: FileStructureElementDiagnosticsCollector,
        moduleComponents: LLFirModuleResolveComponents,
    ): FileStructureElementDiagnosticList
}

internal class ClassDiagnosticRetreiver(
    private val structureElementDeclaration: FirRegularClass
) : FileStructureElementDiagnosticRetriever() {
    override fun retrieve(
        firFile: FirFile,
        collector: FileStructureElementDiagnosticsCollector,
        moduleComponents: LLFirModuleResolveComponents,
    ): FileStructureElementDiagnosticList {
        val sessionHolder = SessionHolderImpl(moduleComponents.session, moduleComponents.scopeSessionProvider.getScopeSession())
        val context = moduleComponents.globalResolveComponents.lockProvider.withGlobalLock(firFile) {
            PersistenceContextCollector.collectContext(sessionHolder, firFile, structureElementDeclaration)
        }
        return withSourceCodeAnalysisExceptionUnwrapping {
            collector.collectForStructureElement(structureElementDeclaration) { components ->
                Visitor(structureElementDeclaration, context, components)
            }
        }
    }

    private class Visitor(
        private val structureElementDeclaration: FirRegularClass,
        context: CheckerContext,
        components: DiagnosticCollectorComponents
    ) : LLFirDiagnosticVisitor(context, components) {

        override fun shouldVisitDeclaration(declaration: FirDeclaration): Boolean {
            return declaration == structureElementDeclaration || shouldDiagnosticsAlwaysBeCheckedOn(declaration)
        }
    }

    companion object {
        fun shouldDiagnosticsAlwaysBeCheckedOn(firElement: FirElement) = when (firElement.source?.kind) {
            KtFakeSourceElementKind.PropertyFromParameter -> true
            KtFakeSourceElementKind.ImplicitConstructor -> true
            else -> false
        }
    }
}

internal class SingleNonLocalDeclarationDiagnosticRetriever(
    private val structureElementDeclaration: FirDeclaration
) : FileStructureElementDiagnosticRetriever() {
    override fun retrieve(
        firFile: FirFile,
        collector: FileStructureElementDiagnosticsCollector,
        moduleComponents: LLFirModuleResolveComponents,
    ): FileStructureElementDiagnosticList {
        val sessionHolder = SessionHolderImpl(moduleComponents.session, moduleComponents.scopeSessionProvider.getScopeSession())
        val context = moduleComponents.globalResolveComponents.lockProvider.withGlobalLock(firFile) {
            PersistenceContextCollector.collectContext(sessionHolder, firFile, structureElementDeclaration)
        }
        return withSourceCodeAnalysisExceptionUnwrapping {
            collector.collectForStructureElement(structureElementDeclaration) { components ->
                Visitor(context, components)
            }
        }
    }

    private class Visitor(
        context: CheckerContext,
        components: DiagnosticCollectorComponents
    ) : LLFirDiagnosticVisitor(context, components) {

        override fun shouldVisitDeclaration(declaration: FirDeclaration): Boolean {
            return true
        }
    }
}

internal object FileDiagnosticRetriever : FileStructureElementDiagnosticRetriever() {
    override fun retrieve(
        firFile: FirFile,
        collector: FileStructureElementDiagnosticsCollector,
        moduleComponents: LLFirModuleResolveComponents,
    ): FileStructureElementDiagnosticList =
        withSourceCodeAnalysisExceptionUnwrapping {
            collector.collectForStructureElement(firFile) { components ->
                Visitor(components, moduleComponents)
            }
        }

    private class Visitor(
        components: DiagnosticCollectorComponents,
        moduleComponents: LLFirModuleResolveComponents,
    ) : LLFirDiagnosticVisitor(
        PersistentCheckerContextFactory.createEmptyPersistenceCheckerContext(
            SessionHolderImpl(moduleComponents.session, moduleComponents.scopeSessionProvider.getScopeSession())
        ),
        components,
    ) {
        override fun visitFile(file: FirFile, data: Nothing?) {
            withAnnotationContainer(file) {
                visitWithDeclaration(file) {
                    file.annotations.forEach { it.accept(this, data) }
                    file.packageDirective.accept(this, data)
                    file.imports.forEach { it.accept(this, data) }
                    // do not visit declarations here
                }
            }
        }
    }
}
