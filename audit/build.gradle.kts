import com.lightningkite.deployhelpers.lkLibrary

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
    alias(libs.plugins.kover)
}

dependencies {
    api(project(":core"))
    api(project(":typed"))
    api(project(":audit-shared"))
    api(libs.services.database)

    ksp(libs.services.database.processor)
    kspTest(libs.services.database.processor)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)

    // Test-only. The auth event log's whole point is that something else raises the events, so
    // proving the seam is actually reached needs the module that raises them. `sessions` does not
    // depend on `audit`, so this stays one-directional.
    testImplementation(project(":sessions"))
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
    description.set("Audit logging for Lightning Server: what was asked, seen, and changed.")
}
