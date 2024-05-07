import org.gradle.api.internal.file.archive.ZipFileTree
import proguard.gradle.ProGuardTask

plugins {
    alias(serverlibs.plugins.kotlinJvm)
    alias(serverlibs.plugins.serialization)
    alias(serverlibs.plugins.ksp)
    application
    alias(serverlibs.plugins.graalVmNative)
    alias(serverlibs.plugins.shadow)
}

group = "com.lightningkite.lightningserver"

dependencies {
    api(project(":server-aws"))
    api(project(":server-azure"))
    api(project(":server-core"))
    api(project(":server-testing"))
    api(project(":server-dynamodb"))
    api(project(":server-firebase"))
    api(project(":server-ktor"))
    api(project(":server-memcached"))
    api(project(":server-mongo"))
    api(project(":server-redis"))
    api(project(":server-sentry"))
    api(project(":server-sftp"))
    ksp(project(":processor"))
    implementation(serverlibs.kotlinerCli)
    implementation(serverlibs.ktorCallLogging)
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
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
    File("${System.getProperty("java.home")}/jmods").listFiles()?.filter { it.extension == "jmod" }?.forEach {
        this.libraryjars(it)
    }
//    this.libraryjars("${System.getProperty("java.home")}/lib/rt.jar".also { println("rt jar is ${it}") })
    this.libraryjars(configurations.runtimeClasspath)
    this.configuration("src/main/proguard.pro")
//    this.keepnames("com.lightningkite.lightningserver.demo.**")
//    this.keepnames("com.lightningkite.lightningserver.demo.AwsHandler")
}