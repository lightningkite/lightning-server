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
    api(libs.serviceAbstractionsCacheDynamodb) { excludeNetty() }
    api(libs.awsS3) { excludeNetty() }
    api(libs.awsLambda) { excludeNetty() }
    api(libs.awsApiGateway) { excludeNetty() }
    api(libs.awsSecretsManager) { excludeNetty() }
    api(libs.lambdaJavaCore) { excludeNetty() }
    api(libs.lambdaJavaEvents) { excludeNetty() }
//    api(libs.lambdaJavaLog4j2) { excludeNetty() }
    api(libs.dynamodb) { excludeNetty() }
    api(libs.orgCrac)
    implementation(libs.coroutinesReactive)
    implementation(libs.coroutinesJdk)
    api(libs.kotlinReflect)
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
    description.set("A set of tools to fill in/replace what Ktor is lacking in.")
}