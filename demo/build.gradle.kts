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
    api(project(":engine-ktor"))
    api(project(":typed"))
    api(project(":engine-aws-serverless"))
    api(project(":engine-jdk-server"))
    api(project(":engine-netty"))
    api(project(":sessions"))
    api(project(":sessions-email"))
//    api(project(":sessions-oauth"))
//    api(project(":sessions-oauth-shared"))
    api(project(":sessions-shared"))
    api(project(":secret-source-aws"))
    api(project(":sessions-sms"))
    api(project(":files"))
    api(project(":ai"))
    api(libs.serviceAbstractionsPubsub)
    api(libs.serviceAbstractionsPubsubRedis)
    api(libs.serviceAbstractionsPubsubAws)
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
    api(libs.serviceAbstractionsEmailInbound)
    api(libs.serviceAbstractionsEmailInboundSendgrid)
    api(libs.serviceAbstractionsEmailInboundMailgun)
    api(libs.serviceAbstractionsEmailInboundSes)
    api(libs.serviceAbstractionsEmailInboundImap)
    api(libs.serviceAbstractionsSmsInbound)
    api(libs.serviceAbstractionsSmsInboundTwilio)
    api(libs.serviceAbstractionsPhonecall)
    api(libs.serviceAbstractionsPhonecallTwilio)
    api(libs.serviceAbstractionsPhonecallTest)
    api(libs.serviceAbstractionsVoiceagentOpenai)

    implementation(libs.kotlinerCli)
    implementation(libs.ktorCallLogging)
    implementation(project(":sessions"))
    testImplementation(libs.kotlinTest)
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

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    isZip64 = true
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
tasks.create("deploy", JavaExec::class.java) {
    group = "deploy"
    dependsOn("lambda")
    classpath(sourceSets.main.get().runtimeClasspath)
    mainClass.set("com.lightningkite.lightningserver.demo.LkEnvDeploy")
    workingDir(project.rootDir)
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