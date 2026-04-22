import com.lightningkite.deployhelpers.*

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
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
    api(libs.services.aws.client) { excludeNetty() }
    api(libs.services.cache.dynamodb) { excludeNetty() }
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
    implementation(libs.coroutines.jdk)
    api(libs.kotlin.reflect)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
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