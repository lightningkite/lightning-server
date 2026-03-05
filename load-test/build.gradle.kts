// by Claude - load test module for framework-native load testing
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.serialization)
}

dependencies {
    api(project(":typed"))
    implementation(libs.coroutinesJdk)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
        optIn.addAll("kotlin.time.ExperimentalTime", "kotlin.uuid.ExperimentalUuidApi")
    }
}
