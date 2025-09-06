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
    api(libs.kotlinXIO)
    api(libs.kotlinXJson)
    api(libs.kotlinXJsonIO)
    api(libs.serializationProperties)
    api(libs.serviceAbstractionsData)
    api(libs.serviceAbstractionsBasis)
    api(libs.serviceAbstractionsShouldBeStandardLibrary)
    api(libs.kotlinHtmlJvm)
    api(project(":core-shared"))

    // Multiplatform cryptography libraries
    api(libs.kotlinxCryptoCore)
    api(libs.kotlinxCryptoOptimal)

    testImplementation(libs.kotlinTestJunit)
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