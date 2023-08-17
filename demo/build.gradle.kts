import org.gradle.api.internal.file.archive.ZipFileTree
import proguard.gradle.ProGuardTask

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
    application
    id("org.graalvm.buildtools.native") version "0.9.24"
    id("com.github.johnrengelman.shadow") version "7.1.0"
}

buildscript {
    dependencies {
        classpath("com.guardsquare:proguard-gradle:7.3.2")
    }
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
    implementation("com.lightningkite:kotliner-cli:1.0.3")
    implementation("io.ktor:ktor-server-call-logging:2.1.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation(project(":client"))
}

kotlin {

    sourceSets.main {
        kotlin.srcDir("build/generated/ksp/main/kotlin")
    }
}

application {
    mainClass.set("com.lightningkite.lightningserver.demo.MainKt")
}

tasks.create("lambda", Sync::class.java) {
    this.destinationDir = project.buildDir.resolve("dist/lambda")
    val jarTask = tasks.getByName("jar")
    dependsOn(jarTask)
    val output = jarTask.outputs.files.find { it.extension == "jar" }!!
    from(zipTree(output))
    duplicatesStrategy = DuplicatesStrategy.WARN
    into("lib") {
        from(configurations.runtimeClasspath) {
            var index = 0
            rename { s -> (index++).toString() + s }
        }
    }
}

tasks.create("proguardTest", ProGuardTask::class) {
    this.injars(tasks.getByName("shadowJar"))
    this.outjars("${buildDir}/outputs/proguarded.jar")
    File("${System.getProperty("java.home")}/jmods").listFiles().filter { it.extension == "jmod" }.forEach {
        this.libraryjars(it)
    }
//    this.libraryjars("${System.getProperty("java.home")}/lib/rt.jar".also { println("rt jar is ${it}") })
    this.libraryjars(configurations.runtimeClasspath)
    this.configuration("src/main/proguard.pro")
//    this.keep("name: 'com.lightningkite.lightningserver.demo.**'")
//    this.keep("com.lightningkite.lightningserver.demo.AwsHandler")
}