import com.lightningkite.deployhelpers.*
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

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
    api(project(":auth"))
    api(project(":typed"))
    api(project(":notifications-shared"))
    api(libs.services.database)
    api(libs.services.email)
    api(libs.services.notifications.fcm)
    api(libs.services.sms)
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

ksp {
    arg("generateFields", "true")
}

kotlin {
    explicitApi()
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        freeCompilerArgs.add("-Xcontext-parameters")
        freeCompilerArgs.add("-Xnested-type-aliases")
    }
    sourceSets.main {
        kotlin.srcDir("build/generated/ksp/main/kotlin")
    }
}

lkLibrary("lightningkite", "lightning-server") {
    description.set("A set of tools to fill in/replace what Ktor is lacking in.")
}