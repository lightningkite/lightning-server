plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    application
    alias(libs.plugins.graalVmNative)
}

group = "com.lightningkite.lightningserver"

dependencies {
    implementation(project(":core"))
    implementation(project(":engine-ktor"))
    implementation(project(":typed"))
    implementation(project(":load-test"))
    implementation(project(":engine-aws-serverless"))
    implementation(project(":deploy-aws-ec2"))
    implementation(project(":engine-jdk-server"))
    implementation(project(":engine-netty"))
    implementation(project(":sessions"))
    implementation(project(":sessions-email"))
//    implementation(project(":sessions-oauth"))
//    implementation(project(":sessions-oauth-shared"))
    implementation(project(":sessions-shared"))
    implementation(project(":secret-source-aws"))
    implementation(project(":sessions-sms"))
    implementation(project(":sessions-totp"))
    implementation(project(":sessions-webauthn"))
    implementation(project(":sessions-oauth"))
    implementation(project(":files"))
    implementation(libs.aws.signin)
    implementation(libs.services.pubsub)
    implementation(libs.services.pubsub.redis)
    implementation(libs.services.pubsub.aws)
    implementation(libs.services.pubsub.test)
    implementation(libs.services.sms)
    implementation(libs.services.sms.test)
    implementation(libs.services.sms.twilio)
    implementation(libs.services.test)
    implementation(libs.services.notifications.test)
    implementation(libs.services.notifications.fcm)
    implementation(libs.services.notifications)
    implementation(libs.services.http.client)
    implementation(libs.services.files.test)
    implementation(libs.services.files.s3)
    implementation(libs.services.files.client)
    implementation(libs.services.files.clamav)
    implementation(libs.services.files)
    implementation(libs.services.email.test)
    implementation(libs.services.email.javasmtp)
    implementation(libs.services.email)
    implementation(libs.services.database.test)
    ksp(libs.services.database.processor)
    implementation(libs.services.database.mongodb)
    implementation(libs.services.database.postgres)
    implementation(libs.services.database.jsonfile)
//    implementation(libs.services.DatabaseCassandra)
    implementation(libs.services.database)
    implementation(libs.services.data)
    implementation(libs.services.cache.test)
    implementation(libs.services.cache.redis)
    implementation(libs.services.cache.memcached)
    implementation(libs.services.cache.dynamodb)
    implementation(libs.services.cache)
    implementation(libs.services.basis)
    implementation(libs.services.aws.client)
    implementation(libs.services.email.inbound)
    implementation(libs.services.email.inbound.sendgrid)
    implementation(libs.services.email.inbound.mailgun)
    implementation(libs.services.email.inbound.ses)
    implementation(libs.services.email.inbound.imap)
    implementation(libs.services.sms.inbound)
    implementation(libs.services.sms.inbound.twilio)
    implementation(libs.services.phonecall)
    implementation(libs.services.phonecall.twilio)
    implementation(libs.services.phonecall.test)
    implementation(libs.services.voiceagent.openai)

    implementation(libs.kotlinerCli)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}

kotlin {
    compilerOptions {
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