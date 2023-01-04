/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.low.level.api.fir.transformers

import org.jetbrains.kotlin.analysis.low.level.api.fir.api.LLFirDesignationToResolve
import org.jetbrains.kotlin.analysis.low.level.api.fir.file.builder.LLFirLockProvider
import org.jetbrains.kotlin.analysis.low.level.api.fir.lazy.resolve.LLFirPhaseUpdater
import org.jetbrains.kotlin.analysis.low.level.api.fir.util.checkPhase
import org.jetbrains.kotlin.fir.FirElementWithResolveState
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.FirTypeAlias
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.transformers.FirProviderInterceptor
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.FirTowerDataContextCollector
import org.jetbrains.kotlin.fir.resolve.transformers.plugin.CompilerRequiredAnnotationsComputationSession
import org.jetbrains.kotlin.fir.resolve.transformers.plugin.FirCompilerRequiredAnnotationsResolveTransformer

internal object LLFirDesignatedAnnotationsResolveTransformed : LLFirLazyPhaseResolver() {

    override fun resolve(
        designation: LLFirDesignationToResolve,
        lockProvider: LLFirLockProvider,
        session: FirSession,
        scopeSession: ScopeSession,
        towerDataContextCollector: FirTowerDataContextCollector?,
        firProviderInterceptor: FirProviderInterceptor?
    ) {
        val resolver =
            LLFirCompilerRequiredAnnotationsResolveTransformer(designation, lockProvider, session, scopeSession)
        resolver.resolve()
    }


    override fun updatePhaseForDeclarationInternals(target: FirElementWithResolveState) {
        LLFirPhaseUpdater.updateDeclarationInternalsPhase(
            target,
            FirResolvePhase.COMPILER_REQUIRED_ANNOTATIONS,
            updateForLocalDeclarations = false
        )
    }

    override fun checkIsResolved(target: FirElementWithResolveState) {
        target.checkPhase(FirResolvePhase.COMPILER_REQUIRED_ANNOTATIONS)
        // todo add proper check that COMPILER_REQUIRED_ANNOTATIONS are resolved
//        checkNestedDeclarationsAreResolved(declaration)
    }
}


private class LLFirCompilerRequiredAnnotationsResolveTransformer(
    designation: LLFirDesignationToResolve,
    lockProvider: LLFirLockProvider,
    session: FirSession,
    scopeSession: ScopeSession,
) : LLFirAbstractMultiDesignationResolver(designation, lockProvider, FirResolvePhase.COMPILER_REQUIRED_ANNOTATIONS) {
    private val transformer =
        FirCompilerRequiredAnnotationsResolveTransformer(session, scopeSession, CompilerRequiredAnnotationsComputationSession())


    override fun withFile(firFile: FirFile, action: () -> Unit) {
        transformer.annotationTransformer.resolveFile(firFile) {
            action()
        }
    }

    override fun withRegularClass(firClass: FirRegularClass, action: () -> Unit) {
        transformer.annotationTransformer.withRegularClass(firClass) {
            action()
        }
    }

    override fun resolveDeclarationContent(target: FirElementWithResolveState) {
        when (target) {
            is FirTypeAlias -> {
                transformer.transformTypeAlias(target, null)
            }
            is FirRegularClass -> {
                transformer.annotationTransformer.resolveRegularClass(
                    target,
                    transformChildren = {
                        target.transformSuperTypeRefs(transformer.annotationTransformer, null)
                    },
                    afterChildrenTransform = {
                        transformer.annotationTransformer.calculateDeprecations(target)
                    }
                )
            }
        }
    }
}