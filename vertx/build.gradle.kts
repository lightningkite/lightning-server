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
    
    // VertX dependencies
    api("io.vertx:vertx-core:4.5.7")
    api("io.vertx:vertx-web:4.5.7")
    api("io.vertx:vertx-web-client:4.5.7")
    api("io.vertx:vertx-lang-kotlin:4.5.7")
    api("io.vertx:vertx-lang-kotlin-coroutines:4.5.7")
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
    description.set("A Vert.x engine implementation for Lightning Server.")
}