import com.lightningkite.deployhelpers.lkLibrary

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(":engine-local"))

    // Ktor dependencies
    api(libs.ktor.core)
    api(libs.ktor.netty)
    api(libs.ktor.websockets)
//    api(libs.ktorCors)

    // Test dependencies
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(testFixtures(project(":engine-local")))
    testImplementation(libs.ktor.test.host)
    testImplementation(libs.ktor.cio.jvm)
    testImplementation(libs.ktor.client.cio.jvm)
    testImplementation(libs.ktor.client.websockets.jvm)
    testImplementation(libs.openTelemetry.sdk.testing)
}


kotlin {
    explicitApi()
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
}

lkLibrary(
    "lightningkite",
    "lightning-server",
    mavenAutomaticRelease = project.findProperty("mavenAutomaticRelease") as? Boolean ?: false
) {
    description.set("A Ktor engine implementation for Lightning Server.")
}