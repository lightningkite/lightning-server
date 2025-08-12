import com.lightningkite.deployhelpers.*

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.serialization)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(":core"))
    api(project(":engine-local"))
    api(libs.serviceAbstractionsDatabase)
    api(libs.serviceAbstractionsCache)
    api(libs.serviceAbstractionsPubsub)
    api(libs.kotlinReflect)
    
    // Ktor dependencies
    api(libs.ktorCore)
    api(libs.ktorNetty)
    api(libs.ktorCioJvm)
    api(libs.ktorWebsockets)
    api(libs.ktorCallLogging)
//    api(libs.ktorCors)
    api(libs.ktorJson)
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