import com.lightningkite.deployhelpers.developer
import com.lightningkite.deployhelpers.github
import com.lightningkite.deployhelpers.mit
import com.lightningkite.deployhelpers.standardPublishing

plugins {
    alias(serverlibs.plugins.kotlinJvm)
    alias(serverlibs.plugins.ksp)
    alias(serverlibs.plugins.dokka)
    alias(serverlibs.plugins.serialization)
    id("signing")
    `maven-publish`
}
group = "com.lightningkite"
dependencies {
    api(project(":server-dynamodb"))
    api(project(":server-core"))
    api(serverlibs.orgCrac)
    api(serverlibs.awsS3)
    api(serverlibs.awsLambda)
    api(serverlibs.awsSes)
    api(serverlibs.awsRds)
    api(serverlibs.awsApiGateway)
    api(serverlibs.awsCloudWatch)
    api(serverlibs.orgCrac)
    api(serverlibs.lambdaJavaCore)
    api(serverlibs.lambdaJavaEvents)
    api(serverlibs.lambdaJavaLog4j2)
    testImplementation(serverlibs.kotlinTest)
    testImplementation(project(":server-testing"))
    ksp(project(":processor"))
    kspTest(project(":processor"))
}
ksp {
    arg("generateFields", "true")
}

kotlin {
    sourceSets.main {
        kotlin.srcDir("build/generated/ksp/main/kotlin")
    }
    sourceSets.test {
        kotlin.srcDir("build/generated/ksp/test/kotlin")
    }
}

tasks.withType<JavaCompile>().configureEach {
    this.targetCompatibility = "11"
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.freeCompilerArgs += "-opt-in=kotlinx.serialization.ExperimentalSerializationApi"
    kotlinOptions.freeCompilerArgs += "-opt-in=kotlin.RequiresOptIn"
    kotlinOptions.jvmTarget = JavaVersion.VERSION_11.toString()
}


standardPublishing {
    name.set("Lightning-server-Server")
    description.set("An implementation of LightningServer Engine using AWS Lambda, FileSystem using AWS S3, Email using AWS SES, and Metrics using AWS CloudWatch.")
    github("lightningkite", "lightning-server")

    licenses {
        mit()
    }

    developers {
        developer(
            id = "LightningKiteJoseph",
            name = "Joseph Ivie",
            email = "joseph@lightningkite.com",
        )
        developer(
            id = "bjsvedin",
            name = "Brady Svedin",
            email = "brady@lightningkite.com",
        )
    }
}
tasks.getByName("sourceJar").dependsOn("kspKotlin")