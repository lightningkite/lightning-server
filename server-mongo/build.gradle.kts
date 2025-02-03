
import com.lightningkite.deployhelpers.github
import com.lightningkite.deployhelpers.mit
import com.lightningkite.deployhelpers.*
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(serverlibs.plugins.kotlinJvm)
    alias(serverlibs.plugins.ksp)
    alias(serverlibs.plugins.serialization)
    // alias(serverlibs.plugins.dokka)
    id("signing")
    alias(serverlibs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(":server-core"))
    implementation(serverlibs.kotlinStdLib)
    implementation(serverlibs.coroutinesCore)
    implementation(serverlibs.coroutinesReactive)
    implementation(serverlibs.embedMongo)
    implementation(serverlibs.mongoDriver)
    ksp(project(":processor"))
    kspTest(project(":processor"))
    testImplementation(project(":server-testing"))
    testImplementation(serverlibs.kotlinTest)
}

ksp {
    arg("generateFields", "true")
}

kotlin {
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
        name.set("Lightning-server-Mongo")
        description.set("An implementation of LightningServer Database using MongoDB.")
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


