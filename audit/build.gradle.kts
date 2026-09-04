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

    // The RealDb* tests run the audit records against a real Postgres rather than the in-memory
    // database the rest of this module's tests use. Zonky's embedded Postgres is used in preference
    // to Testcontainers so the tests need no Docker daemon; when the cluster cannot start they skip.
    // Coordinates are literal rather than catalog entries only to keep this change to one file.
    testImplementation(libs.services.database.postgres)
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
    // The default binaries cover linux/windows/darwin on amd64 only; these add the arm64 hosts.
    testRuntimeOnly("io.zonky.test.postgres:embedded-postgres-binaries-darwin-arm64v8:14.22.0")
    testRuntimeOnly("io.zonky.test.postgres:embedded-postgres-binaries-linux-arm64v8:14.22.0")
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
    description.set("The disclosure audit log: records which fields of which records left the server, for whom.")
}
