
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
    alias(libs.plugins.kover)  // by Claude - coverage reporting
}

dependencies {
    api(libs.kotlinx.io)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.serialization.json.io)
    api(libs.serialization.properties)
    api(sa.data)
    api(sa.basis)
    api(sa.kotlin.bytes.format)
    api(sa.otel.jvm)
    api(sa.webhook.subservice)
    api(sa.pubsub)
    api(libs.kotlin.html.jvm)
    api(project(":core-shared"))

    // Multiplatform cryptography libraries
    api(libs.kotlinx.crypto.core)
    api(libs.kotlinx.crypto.optimal)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.openTelemetry.sdk.testing)
}

kotlin {
    explicitApi()
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
}

mavenPublishing {
    pom { description.set("The core components that make up a Lightning Server framework.") }
}