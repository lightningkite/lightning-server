import com.lightningkite.deployhelpers.lkLibrary

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(":core"))
    api(project(":engine-local"))
    api(libs.services.database)
    api(libs.services.cache)
    api(libs.services.pubsub)
    api(libs.kotlin.reflect)

    // Ktor dependencies
    api(libs.ktor.core)
    api(libs.ktor.netty)
    api(libs.ktor.cio.jvm)
    api(libs.ktor.websockets)
    api(libs.ktor.call.logging)
//    api(libs.ktorCors)
    api(libs.ktor.json)

    // Test dependencies
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.ktor.test.host)
    testImplementation(libs.ktor.client.cio.jvm)
    testImplementation(libs.ktor.client.websockets.jvm)
    testImplementation(libs.openTelemetry.sdk.testing)
}


kotlin {
    explicitApi()
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

lkLibrary(
    "lightningkite",
    "lightning-server",
    mavenAutomaticRelease = project.findProperty("mavenAutomaticRelease") as? Boolean ?: false
) {
    description.set("A Ktor engine implementation for Lightning Server.")
}