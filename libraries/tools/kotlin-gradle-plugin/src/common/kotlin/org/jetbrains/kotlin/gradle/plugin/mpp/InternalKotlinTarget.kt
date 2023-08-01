/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.mpp

import org.gradle.api.provider.Property
import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.gradle.InternalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinTargetComponent
import org.jetbrains.kotlin.tooling.core.HasMutableExtras

internal interface InternalKotlinTarget : KotlinTarget, HasMutableExtras {
    @InternalKotlinGradlePluginApi
    val isSourcesPublishableProperty: Property<Boolean>

    @InternalKotlinGradlePluginApi
    var isSourcesPublishable: Boolean
        get() = isSourcesPublishableProperty.get()
        set(value) = isSourcesPublishableProperty.set(value)

    @InternalKotlinGradlePluginApi
    val kotlinComponents: Set<KotlinTargetComponent>

    @InternalKotlinGradlePluginApi
    override val components: Set<KotlinTargetSoftwareComponent>

    @InternalKotlinGradlePluginApi
    fun onPublicationCreated(publication: MavenPublication)
}

internal val KotlinTarget.internal: InternalKotlinTarget
    get() = (this as? InternalKotlinTarget) ?: throw IllegalArgumentException(
        "KotlinTarget($name) ${this::class} does not implement ${InternalKotlinTarget::class}"
    )
