
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(":typed"))
    api(project(":notifications-shared"))
    api(sa.database)
    api(sa.email)
    api(sa.notifications)
    api(sa.sms)
    api(sa.cache)

    ksp(sa.database.processor)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}


kotlin {
    explicitApi()
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        freeCompilerArgs.add("-Xnested-type-aliases")
    }
    sourceSets.main {
        kotlin.srcDir("build/generated/ksp/main/kotlin")
    }
}

mavenPublishing {
    pom { description.set("A set of tools and endpoints for handling user notifications.") }
}