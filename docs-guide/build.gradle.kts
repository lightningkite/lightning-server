// docs-guide: compiled-samples documentation module.
//
// Each guide chapter lives in guide/*.md.  The actual code examples are
// natural Kotlin functions in src/samples/kotlin/, annotated with
// named regions (// region <tag> … // endregion).  A Kotlin test
// (DriftCheckTest) asserts the fenced code blocks in every .md file are
// byte-identical to the corresponding named regions in the sample sources,
// so changing one without updating the other fails CI.
//
// To run everything:
//   ./gradlew :docs-guide:test

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        freeCompilerArgs.add("-Xcontext-parameters")
    }
    sourceSets.test {
        // Drift-check test lives here
        kotlin.srcDir("src/test/kotlin")
    }
    sourceSets.main {
        // Natural sample functions live here; compiled and exercised from tests
        kotlin.srcDir("src/samples/kotlin")
    }
}

// Declare the guide Markdown directory as an input to the test task so that
// editing a .md file invalidates the Gradle test cache and re-runs DriftCheckTest
// automatically — no --rerun-tasks needed.
tasks.named<Test>("test") {
    inputs.dir(layout.projectDirectory.dir("guide"))
        .withPropertyName("guideMarkdown")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
