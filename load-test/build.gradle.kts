// by Claude - load test module for framework-native load testing
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":typed"))
}

kotlin {
    compilerOptions {
        optIn.addAll("kotlin.time.ExperimentalTime", "kotlin.uuid.ExperimentalUuidApi")
    }
}
