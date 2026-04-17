import com.lightningkite.deployhelpers.*

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
    alias(libs.plugins.kover)
}

dependencies {
    api(project(":core"))
    api(libs.services.cache)
    testImplementation(project(":engine-local"))
    testImplementation(libs.services.cache.test)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}

kotlin {
    explicitApi()
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

lkLibrary("lightningkite", "lightning-server") {
    description.set("Rate limiting for Lightning Server")
}
