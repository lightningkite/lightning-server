
import com.lightningkite.deployhelpers.github
import com.lightningkite.deployhelpers.mit
import com.lightningkite.deployhelpers.*
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ksp)
    // alias(libs.plugins.dokka)
    alias(libs.plugins.serialization)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(":server-core"))
    fun ModuleDependency.excludeNetty() {
        exclude("software.amazon.awssdk:netty-nio-client")
        exclude("software.amazon.awssdk:apache-client")
    }
    api(libs.awsS3) { excludeNetty() }
    api(libs.awsLambda) { excludeNetty() }
    api(libs.awsSes) { excludeNetty() }
    api(libs.awsRds) { excludeNetty() }
    api(libs.awsApiGateway) { excludeNetty() }
    api(libs.awsCloudWatch) { excludeNetty() }
    api(libs.awsCrtClient) { excludeNetty() }
    api(libs.lambdaJavaCore) { excludeNetty() }
    api(libs.lambdaJavaEvents) { excludeNetty() }
    api(libs.lambdaJavaLog4j2) { excludeNetty() }
    api(libs.dynamodb) { excludeNetty() }
    api(libs.orgCrac)
    implementation(libs.coroutinesReactive)
    testImplementation(libs.kotlinTest)
    implementation(libs.coroutinesJdk)
    testImplementation(project(":server-testing"))
    ksp(project(":processor"))
    kspTest(project(":processor"))
}
ksp {
    arg("generateFields", "true")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
    }
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


val lk = project.lk {
    version = gitBasedVersion().also { println("Determined version to be $it") }
}
mavenPublishing {
    // publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates(group.toString(), name, version.toString())
    pom {
        name.set("Lightning-server-Server")
        description.set("An implementation of LightningServer Engine using AWS Lambda, FileSystem using AWS S3, Email using AWS SES, and Metrics using AWS CloudWatch.")
        github("lightningkite", "lightning-server")

        licenses {
            mit()
        }

        developers {
            joseph()
            brady()
        }
    }
}

