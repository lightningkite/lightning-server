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

    implementation(platform(libs.netty.bom))
    implementation(libs.netty.codec.http)
    implementation(libs.netty.handler)
    implementation(libs.netty.transport)
    implementation(libs.netty.buffer)
    implementation(libs.netty.codec)

    // For native transports (compile-time APIs); actual native libs load if present on the platform
    implementation(libs.netty.transport.classes.epoll)
    implementation(libs.netty.transport.classes.kqueue)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(testFixtures(project(":engine-local")))
    testImplementation(libs.okhttp)
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
    description.set("A Netty engine implementation for Lightning Server.")
}