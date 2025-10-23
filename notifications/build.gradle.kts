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
    api(project(":auth"))
    api(project(":typed"))
    api(project(":notifications-shared"))
    api(libs.serviceAbstractionsDatabase)
    api(libs.serviceAbstractionsEmail)
    api(libs.serviceAbstractionsNotificationsFcm)
    api(libs.serviceAbstractionsSms)
    api(libs.serviceAbstractionsCache)
    api(libs.kotlinReflect)

    implementation(libs.oneTimePass)
    implementation(libs.bouncyCastleBcprov)
    implementation(libs.bouncyCastleBcpkix)

    implementation(libs.webauthn4jCore)
    testImplementation(libs.kotlinTest)
    testImplementation(libs.kotlinTestJunit)

    ksp(libs.serviceAbstractionsDatabaseProcessor)
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