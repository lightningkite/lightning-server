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
    alias(libs.plugins.ksp)
}

dependencies {
    // Every module a guide chapter shows.
    implementation(project(":core"))
    implementation(project(":typed"))
    implementation(project(":auth"))
    implementation(project(":audit"))
    implementation(project(":sessions"))
    implementation(project(":sessions-email"))
    implementation(project(":sessions-sms"))
    implementation(project(":sessions-totp"))
    implementation(project(":sessions-oauth"))
    implementation(project(":files"))
    implementation(project(":media"))
    implementation(project(":ratelimit"))
    implementation(project(":notifications"))
    implementation(project(":engine-local"))
    implementation(project(":engine-ktor"))
    implementation(project(":engine-netty"))
    implementation(project(":engine-jdk-server"))
    implementation(project(":engine-aws-serverless"))
    implementation(project(":deploy-aws-ec2"))
    implementation(project(":secret-source-aws"))
    implementation(sa.database)
    implementation(sa.database.jsonfile)
    implementation(sa.database.mongodb)
    implementation(sa.cache)
    implementation(sa.cache.redis)
    implementation(sa.cache.dynamodb)
    implementation(sa.pubsub.redis)
    implementation(sa.files.s3)
    ksp(sa.database.processor)

    // kotlin.test.junit is promoted to implementation so @Test is available in src/samples/kotlin
    // (the drift-checked sample source), letting guide examples show complete annotated test classes.
    implementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlin.test.junit)
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
    sourceSets.test {
        // Drift-check test lives here
        kotlin.srcDir("src/test/kotlin")
    }
    sourceSets.main {
        // Natural sample functions live here; compiled and exercised from tests
        kotlin.srcDir("src/samples/kotlin")
        // KSP-generated code for @Queryable (used in Ch5 database samples)
        kotlin.srcDir("build/generated/ksp/main/kotlin")
    }
}

// Declare the guide Markdown directory as an input to the test task so that
// editing a .md file invalidates the Gradle test cache and re-runs DriftCheckTest
// automatically — no --rerun-tasks needed.
tasks.named<Test>("test") {
    inputs.dir(layout.projectDirectory.dir("guide"))
        .withPropertyName("guideMarkdown")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.layout.projectDirectory.file("README.md"))
        .withPropertyName("readme")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
