import com.lightningkite.deployhelpers.*
import org.gradle.api.internal.file.archive.ZipFileTree
import proguard.gradle.ProGuardTask
import java.util.*

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.serialization)
    alias(libs.plugins.ksp)
    application
    alias(libs.plugins.graalVmNative)
    alias(libs.plugins.shadow)
}

val lk = project.lk {}

group = "com.lightningkite.lightningserver"

dependencies {
    api(project(":shared"))
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
    implementation(libs.kotlinerCli)
    implementation(libs.ktorCallLogging)
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
}

kotlin {

    sourceSets.main {
        kotlin.srcDir("build/generated/ksp/main/kotlin")
    }
}

application {
    mainClass.set("com.lightningkite.lightningserver.monitoring.MainKt")
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
    mainClass.set("com.lightningkite.lightningserver.monitoring.MainKt")
    args("terraform")
    workingDir(project.rootDir)
}
tasks.create("serve", JavaExec::class.java) {
    group = "application"
    classpath(sourceSets.main.get().runtimeClasspath)
    mainClass.set("com.lightningkite.lightningserver.monitoring.MainKt")
    args("serve")
    workingDir(project.rootDir)
}
tasks.withType(Zip::class) {
    isZip64 = true
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
env("lk", "lk")
