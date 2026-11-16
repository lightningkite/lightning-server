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
    implementation(sa.pubsub)
    implementation(sa.pubsub.redis)
    implementation(sa.pubsub.aws)
    implementation(sa.pubsub.test)
    implementation(sa.sms)
    implementation(sa.sms.test)
    implementation(sa.sms.twilio)
    implementation(sa.test)
    implementation(sa.notifications.test)
    implementation(sa.notifications.fcm)
    implementation(sa.notifications)
    implementation(sa.http.client)
    implementation(sa.files.test)
    implementation(sa.files.s3)
    implementation(sa.files.client)
    implementation(sa.files.clamav)
    implementation(sa.files)
    implementation(sa.email.test)
    implementation(sa.email.javasmtp)
    implementation(sa.email)
    implementation(sa.database.test)
    ksp(sa.database.processor)
    implementation(sa.database.mongodb)
    implementation(sa.database.postgres)
    implementation(sa.database.jsonfile)
//    implementation(sa.DatabaseCassandra)
    implementation(sa.database)
    implementation(sa.data)
    implementation(sa.cache.test)
    implementation(sa.cache.redis)
    implementation(sa.cache.memcached)
    implementation(sa.cache.dynamodb)
    implementation(sa.cache)
    implementation(sa.basis)
    implementation(sa.aws.client)
    implementation(sa.email.inbound)
    implementation(sa.email.inbound.sendgrid)
    implementation(sa.email.inbound.mailgun)
    implementation(sa.email.inbound.ses)
    implementation(sa.email.inbound.imap)
    implementation(sa.sms.inbound)
    implementation(sa.sms.inbound.twilio)
    implementation(sa.phonecall)
    implementation(sa.phonecall.twilio)
    implementation(sa.phonecall.test)
    implementation(sa.voiceagent.openai)

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