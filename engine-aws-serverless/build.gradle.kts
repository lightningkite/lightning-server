
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    api(sa.aws.client) { excludeNetty() }
    api(sa.cache.dynamodb) { excludeNetty() }
    api(libs.aws.s3) { excludeNetty() }
    api(libs.aws.lambda) { excludeNetty() }
    api(libs.aws.apiGateway) { excludeNetty() }
    api(libs.aws.secrets.manager) { excludeNetty() }
    api(libs.lambda.java.core) { excludeNetty() }
    api(libs.lambda.java.events) { excludeNetty() }
//    api(libs.lambdaJavaLog4j2) { excludeNetty() }
    api(libs.dynamodb) { excludeNetty() }
    api(libs.orgCrac)
    implementation(libs.coroutines.reactive)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}

kotlin {
    explicitApi()
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
}

mavenPublishing {
    pom { description.set("An engine implementation and deployment tools for deploying a Lightning Server project in AWS Lambda.") }
}