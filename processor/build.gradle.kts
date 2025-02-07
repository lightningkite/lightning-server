import com.lightningkite.deployhelpers.*
import com.vanniktech.maven.publish.*

plugins {
    alias(libs.plugins.kotlinJvm)
    // alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}


val kotlinVersion: String by project
val kspVersion: String by project

dependencies {
    implementation(libs.ksp)
    implementation(libs.kotlinCompiler)
    testImplementation(libs.kotlinTest)
}

val lk = project.lk {}

mavenPublishing {
    // publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates(group.toString(), name, version.toString())
    pom {
        name.set("Lightning-server-Processor")
        description.set("A tool for communication between a server and a client built around Ktor Servers")
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