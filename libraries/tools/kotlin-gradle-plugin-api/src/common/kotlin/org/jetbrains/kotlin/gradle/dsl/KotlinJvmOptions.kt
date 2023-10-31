// DO NOT EDIT MANUALLY!
// Generated by org/jetbrains/kotlin/generators/arguments/GenerateGradleOptions.kt
// To regenerate run 'generateGradleOptions' task
@file:Suppress("RemoveRedundantQualifierName", "Deprecation", "DuplicatedCode")

package org.jetbrains.kotlin.gradle.dsl

interface KotlinJvmOptions : org.jetbrains.kotlin.gradle.dsl.KotlinCommonOptions {
    override val options: org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions

    /**
     * Generate metadata for Java 1.8 reflection on method parameters.
     * Default value: false
     */
    var javaParameters: kotlin.Boolean
        get() = options.javaParameters.get()
        set(value) = options.javaParameters.set(value)

    private val kotlin.String?.jvmTargetCompilerOption get() = if (this != null) org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(this) else null

    private val org.jetbrains.kotlin.gradle.dsl.JvmTarget.jvmTargetKotlinOption get() = this.target

    /**
     * The target version of the generated JVM bytecode (1.8 and 9–21), with 1.8 as the default.
     * Possible values: "1.8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21"
     * Default value: JvmTarget.DEFAULT
     */
    var jvmTarget: kotlin.String
        get() = options.jvmTarget.get().jvmTargetKotlinOption
        set(value) = options.jvmTarget.set(value.jvmTargetCompilerOption)

    /**
     * Name of the generated '.kotlin_module' file.
     * Default value: null
     */
    var moduleName: kotlin.String?
        get() = options.moduleName.orNull
        set(value) = options.moduleName.set(value)

    /**
     * Don't automatically include the Java runtime in the classpath.
     * Default value: false
     */
    var noJdk: kotlin.Boolean
        get() = options.noJdk.get()
        set(value) = options.noJdk.set(value)
}
