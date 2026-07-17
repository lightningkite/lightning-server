plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    application
    alias(libs.plugins.graalVmNative)
//    alias(libs.plugins.shadow)
}

group = "com.lightningkite.lightningserver"

dependencies {
    api(project(":core"))
    api(project(":engine-ktor"))
    api(project(":typed"))
    api(project(":load-test"))
    api(project(":engine-aws-serverless"))
    api(project(":deploy-aws-ec2"))
    api(project(":engine-jdk-server"))
    api(project(":engine-netty"))
    api(project(":sessions"))
    api(project(":sessions-email"))
//    api(project(":sessions-oauth"))
//    api(project(":sessions-oauth-shared"))
    api(project(":sessions-shared"))
    api(project(":secret-source-aws"))
    api(project(":sessions-sms"))
    api(project(":sessions-oauth"))
    api(project(":files"))
    api(libs.services.pubsub)
    api(libs.services.pubsub.redis)
    api(libs.services.pubsub.aws)
    api(libs.services.pubsub.test)
    api(libs.services.sms)
    api(libs.services.sms.test)
    api(libs.services.sms.twilio)
    api(libs.services.test)
    api(libs.services.notifications.test)
    api(libs.services.notifications.fcm)
    api(libs.services.notifications)
    api(libs.services.http.client)
    api(libs.services.files.test)
    api(libs.services.files.s3)
    api(libs.services.files.client)
    api(libs.services.files.clamav)
    api(libs.services.files)
    api(libs.services.email.test)
    api(libs.services.email.javasmtp)
    api(libs.services.email)
    api(libs.services.database.test)
    ksp(libs.services.database.processor)
    api(libs.services.database.mongodb)
    api(libs.services.database.postgres)
    api(libs.services.database.jsonfile)
//    api(libs.services.DatabaseCassandra)
    api(libs.services.database)
    api(libs.services.data)
    api(libs.services.cache.test)
    api(libs.services.cache.redis)
    api(libs.services.cache.memcached)
    api(libs.services.cache.dynamodb)
    api(libs.services.cache)
    api(libs.services.basis)
    api(libs.services.aws.client)
    api(libs.services.email.inbound)
    api(libs.services.email.inbound.sendgrid)
    api(libs.services.email.inbound.mailgun)
    api(libs.services.email.inbound.ses)
    api(libs.services.email.inbound.imap)
    api(libs.services.sms.inbound)
    api(libs.services.sms.inbound.twilio)
    api(libs.services.phonecall)
    api(libs.services.phonecall.twilio)
    api(libs.services.phonecall.test)
    api(libs.services.voiceagent.openai)

    implementation(libs.kotlinerCli)
    implementation(libs.ktor.call.logging)
    implementation(project(":sessions"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
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

//tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
//    isZip64 = true
//}

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

// by Claude - run load test against a running demo server
tasks.create("loadTest", JavaExec::class.java) {
    group = "application"
    classpath(sourceSets.main.get().runtimeClasspath)
    mainClass.set("com.lightningkite.lightningserver.demo.LoadTestMainKt")
    workingDir(project.rootDir)
}
// by Claude - compare all engines with the same load test
tasks.create("engineComparison", JavaExec::class.java) {
    group = "application"
    classpath(sourceSets.main.get().runtimeClasspath)
    mainClass.set("com.lightningkite.lightningserver.demo.EngineComparisonMainKt")
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

//tasks.create("proguardTest", ProGuardTask::class) {
//    this.injars(tasks.getByName("shadowJar"))
//    this.outjars("${buildDir}/outputs/proguarded.jar")
//    File("${System.getProperty("java.home")}/jmods").listFiles()?.filter { it.extension == "jmod" }?.forEach {
//        this.libraryjars(it)
//    }
////    this.libraryjars("${System.getProperty("java.home")}/lib/rt.jar".also { println("rt jar is ${it}") })
//    this.libraryjars(configurations.runtimeClasspath)
//    this.configuration("src/main/proguard.pro")
////    this.keepnames("com.lightningkite.lightningserver.demo.**")
////    this.keepnames("com.lightningkite.lightningserver.demo.AwsHandler")
//}