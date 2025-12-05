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
    api(project(":typed"))
    api(libs.serviceAbstractionsDatabase)
    api(libs.serviceAbstractionsFiles)
    api(libs.serviceAbstractionsAiKoog)
    api(libs.serviceAbstractionsAiKoogAwsOpensearch)
    api(libs.kotlinReflect)

    // SMS and Email support for external channels
    api(libs.serviceAbstractionsSms)
    api(libs.serviceAbstractionsSmsInbound)
    api(libs.serviceAbstractionsEmail)
    api(libs.serviceAbstractionsEmailInbound)

    // Direct Koog dependency for session management API
    api("ai.koog:koog-agents:0.5.4-SNAPSHOT")

    testImplementation(libs.kotlinTest)
    testImplementation(libs.kotlinTestJunit)
    testImplementation(libs.serviceAbstractionsDatabaseTest)
    testImplementation(libs.serviceAbstractionsDatabaseJsonfile)
    testImplementation(project(":engine-local"))

    configurations.filter { it.name.startsWith("ksp") }.forEach {
        add(it.name, libs.serviceAbstractionsDatabaseProcessor)
    }
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
    description.set("AI tools for Lightning Server including chatbots with database access.")
}
