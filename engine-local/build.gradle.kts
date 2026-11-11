
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    id("signing")
    `java-test-fixtures`
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(":core"))
    api(sa.database)
    api(sa.cache)
    api(sa.pubsub)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)

    // The cross-engine HTTP conformance suite lives in test fixtures so every engine module's test
    // source set can run the identical assertions against its own engine (see EngineHttpConformanceSuite).
    testFixturesApi(libs.kotlin.test)
    testFixturesApi(libs.kotlin.test.junit)
}


kotlin {
    explicitApi()
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
}

mavenPublishing {
    pom { description.set("The abstract engine implementation to use in dedicated deployments.") }
}