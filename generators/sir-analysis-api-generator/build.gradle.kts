plugins {
    kotlin("jvm")
    id("jps-compatible")
}

sourceSets {
    "main" { java.srcDirs("main") }
    "test" { projectDefault() }
}

dependencies {
    api(kotlinStdlib())

    testImplementation(projectTests(":generators:test-generator"))
    testImplementation(projectTests(":compiler:tests-common"))
    testImplementation(projectTests(":compiler:tests-common-new"))
    testImplementation(projectTests(":compiler:tests-spec"))

    testApi(projectTests(":native:swift:sir-analysis-api"))
    testImplementation(intellijCore())
    testApi(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

val generateSirAnalysisApiTests by generator("org.jetbrains.kotlin.generators.tests.native.swift.sir.analysis.api.GenerateSirAnalysisApiTestsKt")

testsJar()

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-receivers")
    }
}


projectTest(jUnitMode = JUnitMode.JUnit5) {
    workingDir = rootDir
    useJUnitPlatform { }
}
