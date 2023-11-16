plugins {
    kotlin("jvm")
}

description = "SIR to Kotlin bindings generator"

dependencies {
    compileOnly(kotlinStdlib())

    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.junit4)
    testImplementation(projectTests(":compiler:tests-common"))
    testImplementation(projectTests(":compiler:tests-common-new"))
}

sourceSets {
    "main" { projectDefault() }
    "test" { projectDefault() }
}
//
//projectTest(jUnitMode = JUnitMode.JUnit5) {
//    workingDir = rootDir
//    useJUnitPlatform { }
//}