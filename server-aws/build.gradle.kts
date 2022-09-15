import com.lightningkite.deployhelpers.developer
import com.lightningkite.deployhelpers.github
import com.lightningkite.deployhelpers.mit
import com.lightningkite.deployhelpers.standardPublishing

plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp")
    kotlin("plugin.serialization")
    id("org.jetbrains.dokka")
    id("signing")
    `maven-publish`
}

val kotlinVersion: String by project
val khrysalisVersion: String by project
val coroutines: String by project
val awsVersion = "2.17.261"
dependencies {
    api(project(":server-dynamodb"))
    api(project(":server-core"))
    api("software.amazon.awssdk:s3:$awsVersion")
    api("software.amazon.awssdk:lambda:$awsVersion")
    api("software.amazon.awssdk:ses:$awsVersion")
    api("software.amazon.awssdk:rds:$awsVersion")
    api("software.amazon.awssdk:apigatewaymanagementapi:$awsVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:$coroutines")
    api("com.amazonaws:aws-lambda-java-core:1.2.1")
    api("com.amazonaws:aws-lambda-java-events:3.11.0")
    runtimeOnly("com.amazonaws:aws-lambda-java-log4j2:1.5.1")
    testImplementation("org.jetbrains.kotlin:kotlin-test:$kotlinVersion")
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
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.freeCompilerArgs += "-opt-in=kotlinx.serialization.ExperimentalSerializationApi"
    kotlinOptions.freeCompilerArgs += "-opt-in=kotlin.RequiresOptIn"
}


standardPublishing {
    name.set("Lightning-server-Server")
    description.set("A set of tools to fill in/replace what Ktor is lacking in.")
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