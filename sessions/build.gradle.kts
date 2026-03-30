import com.lightningkite.deployhelpers.*

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
    alias(libs.plugins.kover)  // by Claude - coverage reporting
}

dependencies {
    api(project(":core"))
    api(project(":auth"))
    api(project(":typed"))
    api(project(":sessions-shared"))
    api(libs.services.database)
    api(libs.services.cache)
    api(libs.kotlin.reflect)

    implementation(libs.oneTimePass)
    implementation(libs.bouncy.castle.bcprov)
    implementation(libs.bouncy.castle.bcpkix)

    implementation(libs.webauthn4j.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)

    ksp(libs.services.database.processor)
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