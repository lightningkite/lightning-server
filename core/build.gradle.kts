import com.lightningkite.deployhelpers.*

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
    alias(libs.plugins.kover)  // by Claude - coverage reporting
}

dependencies {
    api(libs.kotlinx.io)
    api(libs.kotlinx.json)
    api(libs.kotlinx.json.io)
    api(libs.serialization.properties)
    api(libs.services.data)
    api(libs.services.basis)
    api(libs.services.otel.jvm)
    api(libs.services.pubsub)
    api(libs.services.shouldBeStandardLibrary)
    api(libs.kotlin.html.jvm)
    api(project(":core-shared"))

    // Multiplatform cryptography libraries
    api(libs.kotlinx.crypto.core)
    api(libs.kotlinx.crypto.optimal)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}

ksp {
    arg("generateFields", "true")
}

kotlin {
    explicitApi()
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        freeCompilerArgs.add("-Xcontext-parameters")
    }
    sourceSets.main {
        kotlin.srcDir("build/generated/ksp/main/kotlin")
    }
    sourceSets.test {
        kotlin.srcDir("build/generated/ksp/test/kotlin")
    }
}

lkLibrary("lightningkite", "lightning-server") {
    description.set("A set of tools to fill in/replace what Ktor is lacking in.")
}