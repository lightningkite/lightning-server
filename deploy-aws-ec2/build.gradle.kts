import com.lightningkite.deployhelpers.*

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.serialization)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(":core"))
    fun ModuleDependency.excludeNetty() {
        exclude("software.amazon.awssdk:netty-nio-client")
        exclude("software.amazon.awssdk:apache-client")
    }
    api(libs.serviceAbstractionsAwsClient) { excludeNetty() }
    api(libs.awsS3) { excludeNetty() }
    api(libs.awsSecretsManager) { excludeNetty() }
    api("software.amazon.awssdk:sqs:2.40.3") { excludeNetty() }
    implementation(libs.coroutinesReactive)
    implementation(libs.coroutinesJdk)
    api(libs.kotlinReflect)
    testImplementation(libs.kotlinTest)
    testImplementation(libs.kotlinTestJunit)
}

ksp {
    arg("generateFields", "true")
}

kotlin {
    explicitApi()
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        freeCompilerArgs.add("-Xcontext-parameters")
    }
    sourceSets.main {
        kotlin.srcDir("build/generated/ksp/main/kotlin")
    }
    sourceSets.test {
        kotlin.srcDir("build/generated/ksp/test/kotlin")
    }
}

lkLibrary("lightningkite", "lightning-server") {
    description.set("AWS EC2 deployment with Auto Scaling and Load Balancing for Lightning Server.")
}
