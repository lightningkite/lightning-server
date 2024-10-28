import org.gradle.api.internal.file.archive.ZipFileTree
import proguard.gradle.ProGuardTask
import java.util.*

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


tasks.create("lambda", Copy::class.java) {
    group = "deploy"
    this.destinationDir = project.buildDir.resolve("dist/lambda")
    val jarTask = tasks.getByName("jar")
    dependsOn(jarTask)
    val output = jarTask.outputs.files.find { it.extension == "jar" }!!
    from(zipTree(output))
    into("lib") {
        from(configurations.runtimeClasspath)
    }
}
tasks.create("rebuildTerraform", JavaExec::class.java) {
    group = "deploy"
    classpath(sourceSets.main.get().runtimeClasspath)
    mainClass.set("com.lightningkite.lightningserverdemo.MainKt")
    args("terraform")
    workingDir(project.rootDir)
    inputs.files(*file("terraform").walkTopDown().filter { it.name == "project.json" }.toList().toTypedArray())
}

fun env(name: String, profile: String) {
    val mongoProfile = file("${System.getProperty("user.home")}/.mongo/profiles/$profile.env")

    if(mongoProfile.exists()) {
        tasks.create("deployServer${name}Init", Exec::class.java) {
            group = "deploy"
            this.dependsOn("lambda", "rebuildTerraform")
            this.environment("AWS_PROFILE", "$profile")
            val props = Properties()
            mongoProfile.reader().use { props.load(it) }
            props.entries.forEach {
                environment(it.key.toString().trim('"', ' '), it.value.toString().trim('"', ' '))
            }
            this.executable = "terraform"
            this.args("init")
            this.workingDir = file("terraform/$name")
        }
        tasks.create("deployServer${name}", Exec::class.java) {
            group = "deploy"
            this.dependsOn("deployServer${name}Init")
            this.environment("AWS_PROFILE", "$profile")
            val props = Properties()
            mongoProfile.reader().use { props.load(it) }
            props.entries.forEach { environment(it.key.toString().trim('"', ' '), it.value.toString().trim('"', ' ')) }
            this.executable = "terraform"
            this.args("apply", "-auto-approve")
            this.workingDir = file("terraform/$name")
        }
    }
}
env("example", "default")
env("lkec2", "lk")

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