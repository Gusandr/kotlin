// DO NOT EDIT MANUALLY!
// Generated by org/jetbrains/kotlin/generators/arguments/GenerateGradleOptions.kt
// To regenerate run 'generateGradleOptions' task
@file:Suppress("RemoveRedundantQualifierName", "Deprecation", "DuplicatedCode")

package org.jetbrains.kotlin.gradle.dsl

interface KotlinCommonCompilerToolOptions {

    /**
     * Report an error if there are any warnings.
     * Default value: false
     */
    @get:org.gradle.api.tasks.Input
    val allWarningsAsErrors: org.gradle.api.provider.Property<kotlin.Boolean>

    /**
     * Don't generate any warnings.
     * Default value: false
     */
    @get:org.gradle.api.tasks.Internal
    val suppressWarnings: org.gradle.api.provider.Property<kotlin.Boolean>

    /**
     * Enable verbose logging output.
     * Default value: false
     */
    @get:org.gradle.api.tasks.Internal
    val verbose: org.gradle.api.provider.Property<kotlin.Boolean>

    /**
     * A list of additional compiler arguments
     * Default value: emptyList<String>()
     */
    @get:org.gradle.api.tasks.Input
    val freeCompilerArgs: org.gradle.api.provider.ListProperty<kotlin.String>
}
