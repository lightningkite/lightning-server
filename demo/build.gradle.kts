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

group = "com.lightningkite.lightningserver"

dependencies {
    api(project(":core"))
    api(project(":ktor"))
    api(project(":typed"))
    api(project(":aws-serverless"))
    api(project(":jdk-server"))
    api(project(":netty"))
    api(project(":sessions"))
    api(project(":sessions-email"))
//    api(project(":sessions-oauth"))
//    api(project(":sessions-oauth-shared"))
    api(project(":sessions-shared"))
    api(project(":sessions-sms"))
    api(project(":files"))
    api(libs.serviceAbstractionsPubsub)
    api(libs.serviceAbstractionsPubsubRedis)
    api(libs.serviceAbstractionsPubsubTest)
    api(libs.serviceAbstractionsShouldBeStandardLibrary)
    api(libs.serviceAbstractionsSms)
    api(libs.serviceAbstractionsSmsTest)
    api(libs.serviceAbstractionsSmsTwilio)
    api(libs.serviceAbstractionsTest)
    api(libs.serviceAbstractionsNotificationsTest)
    api(libs.serviceAbstractionsNotificationsFcm)
    api(libs.serviceAbstractionsNotifications)
    api(libs.serviceAbstractionsHttpClient)
    api(libs.serviceAbstractionsFilesTest)
    api(libs.serviceAbstractionsFilesS3)
    api(libs.serviceAbstractionsFilesClient)
    api(libs.serviceAbstractionsFilesClamav)
    api(libs.serviceAbstractionsFiles)
    api(libs.serviceAbstractionsEmailTest)
    api(libs.serviceAbstractionsEmailJavasmtp)
    api(libs.serviceAbstractionsEmail)
    api(libs.serviceAbstractionsDatabaseTest)
    ksp(libs.serviceAbstractionsDatabaseProcessor)
    api(libs.serviceAbstractionsDatabaseMongodb)
    api(libs.serviceAbstractionsDatabasePostgres)
    api(libs.serviceAbstractionsDatabaseJsonfile)
    api(libs.serviceAbstractionsDatabase)
    api(libs.serviceAbstractionsData)
    api(libs.serviceAbstractionsCacheTest)
    api(libs.serviceAbstractionsCacheRedis)
    api(libs.serviceAbstractionsCacheMemcached)
    api(libs.serviceAbstractionsCacheDynamodb)
    api(libs.serviceAbstractionsCache)
    api(libs.serviceAbstractionsBasis)
    api(libs.serviceAbstractionsAwsClient)

    implementation(libs.kotlinerCli)
    implementation(libs.ktorCallLogging)
    implementation(project(":sessions"))
    testImplementation(libs.kotlinTestJunit)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
    sourceSets.main {
        kotlin.srcDir("build/generated/ksp/main/kotlin")
    }
}

application {
    mainClass.set("com.lightningkite.lightningserver.demo.MainKt")
    this.applicationName = "server"
}

tasks.create("serve", JavaExec::class.java) {
    group = "application"
    classpath(sourceSets.main.get().runtimeClasspath)
    mainClass.set("com.lightningkite.lightningserver.demo.MainKt")
    args("serve")
    workingDir(project.rootDir)
}

tasks.create("serveJdk", JavaExec::class.java) {
    group = "application"
    classpath(sourceSets.main.get().runtimeClasspath)
    mainClass.set("com.lightningkite.lightningserver.demo.MainKt")
    args("serveJdk")
    workingDir(project.rootDir)
}
tasks.create("serveNetty", JavaExec::class.java) {
    group = "application"
    classpath(sourceSets.main.get().runtimeClasspath)
    mainClass.set("com.lightningkite.lightningserver.demo.MainKt")
    args("serveNetty")
    workingDir(project.rootDir)
}

tasks.create("lambda", Sync::class.java) {
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
    mainClass.set("com.lightningkite.lightningserver.demo.MainKt")
    args("terraform")
    workingDir(project.rootDir)
}
tasks.create("sdk", JavaExec::class.java) {
    group = "deploy"
    classpath(sourceSets.main.get().runtimeClasspath)
    mainClass.set("com.lightningkite.lightningserver.demo.MainKt")
    args("sdk")
    workingDir(project.rootDir)
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