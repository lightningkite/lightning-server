import org.gradle.api.internal.file.archive.ZipFileTree

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
    application
}

group = "com.lightningkite.lightningserver"

repositories {
    mavenLocal()
    maven(url = "https://s01.oss.sonatype.org/content/repositories/snapshots/")
    maven(url = "https://s01.oss.sonatype.org/content/repositories/releases/")
    maven { url = uri("https://maven.pkg.jetbrains.space/public/p/kotlinx-html/maven") }
    maven { url = uri("https://maven.pkg.jetbrains.space/public/p/ktor/eap") }
    mavenCentral()
}

dependencies {
    api(project(":server"))
    ksp(project(":processor"))
    implementation("io.ktor:ktor-server-call-logging:2.1.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation(project(":client"))
}

kotlin {
    sourceSets.main {
        kotlin.srcDir("build/generated/ksp/main/kotlin")
    }
}

tasks.create("buildZip", Zip::class.java) {
    archiveFileName.set("lambda.zip")
    destinationDirectory.set(project.buildDir.resolve("dist"))
    val jarTask = tasks.getByName("jar")
    dependsOn(jarTask)
    val output = jarTask.outputs.files.find { it.extension == "jar" }!!
    from(zipTree(output))
    into("lib") {
        from(configurations.runtimeClasspath)
    }
}