import com.lightningkite.deployhelpers.*

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.serialization)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
    alias(libs.plugins.kover)
}

dependencies {
    api(project(":ratelimit-shared"))
    api(project(":core"))
    api(libs.serviceAbstractionsCache)
    testImplementation(project(":engine-local"))
    testImplementation(libs.serviceAbstractionsCacheTest)
    testImplementation(libs.kotlinTest)
    testImplementation(libs.kotlinTestJunit)
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
