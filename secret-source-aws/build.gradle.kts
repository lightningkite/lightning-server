import com.lightningkite.deployhelpers.*

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.serialization)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(":core"))
    fun ModuleDependency.excludeNetty() {
        exclude("software.amazon.awssdk:netty-nio-client")
        exclude("software.amazon.awssdk:apache-client")
    }
    api(libs.awsSecrets) { excludeNetty() }
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
    description.set("A set of tools to fill in/replace what Ktor is lacking in.")
}