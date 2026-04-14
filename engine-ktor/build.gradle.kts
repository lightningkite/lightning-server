import com.lightningkite.deployhelpers.*

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
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
    description.set("A Ktor engine implementation for Lightning Server.")
}