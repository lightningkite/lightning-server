// docs-guide: a self-contained module whose only purpose is to hold verified
// documentation chapters.  The knit plugin extracts code fences from the
// Markdown guides into real .kt example files, then generates a JUnit test
// that compiles and runs them.  CI therefore catches silent example rot.
//
// Contents of guide/ are processed by `./gradlew :docs-guide:knit`.
// The generated files (example-*.kt, *Test.kt) are committed so that
// `./gradlew :docs-guide:test` verifies them on every build without needing
// the knit task to run again.

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    id("kotlinx-knit")
}

// Knit needs the kotlin plugin applied first (already done above) and then
// points at the guide directory for Markdown input.
knit {
    rootDir = project.rootDir
    files = fileTree(project.projectDir) {
        include("guide/**/*.md")
    }
}

dependencies {
    // The modules a "getting started" example needs:
    implementation(project(":core"))
    implementation(project(":typed"))
    implementation(project(":auth"))
    implementation(project(":engine-local"))
    implementation(libs.services.database.jsonfile)
    implementation(libs.services.database)
    implementation(libs.services.cache)

    // Knit test runner — executes and captures stdout from generated examples
    testImplementation(libs.knit.test)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        freeCompilerArgs.add("-Xcontext-parameters")
    }
    // Generated example files go here; Kotlin must see this as a source root
    sourceSets.test {
        kotlin.srcDir("src/test/kotlin")
    }
}
