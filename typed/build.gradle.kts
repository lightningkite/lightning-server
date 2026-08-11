import com.lightningkite.deployhelpers.lkLibrary

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
    api(project(":typed-shared"))
    api(libs.services.database)
    api(libs.services.cache)
    api(libs.services.http.client)
    api(libs.kotlin.reflect)
    ksp(libs.services.database.processor)
    // The test source set declares its own models, and the processor no longer
    // covers test sources via the main `ksp` configuration.
    kspTest(libs.services.database.processor)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.openTelemetry.sdk.testing)
}


kotlin {
    explicitApi()
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
    sourceSets.main {
        kotlin.srcDir("build/generated/ksp/main/kotlin")
    }
    sourceSets.test {
        kotlin.srcDir("build/generated/ksp/test/kotlin")
    }
}

lkLibrary(
    "lightningkite",
    "lightning-server",
    mavenAutomaticRelease = project.findProperty("mavenAutomaticRelease") as? Boolean ?: false
) {
    description.set("A set of tools for typing, documenting, and requiring authentication in endpoints.")
}

// Wire the docs-guide compiled samples into Dokka so @sample references resolve.
// The samples source set in docs-guide/src/samples/kotlin is compiled independently
// by docs-guide; here we add it as a Dokka samples source root so that @sample
// tags in this module's KDoc can reference functions defined there.
dokka {
    dokkaSourceSets.configureEach {
        samples.from(rootProject.file("docs-guide/src/samples/kotlin"))
    }
}