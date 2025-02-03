import com.lightningkite.deployhelpers.*
import com.vanniktech.maven.publish.*

plugins {
    alias(serverlibs.plugins.kotlinJvm)
    // alias(serverlibs.plugins.dokka)
    id("signing")
    alias(serverlibs.plugins.vanniktechMavenPublish)
}


val kotlinVersion: String by project
val kspVersion: String by project

dependencies {
    implementation(serverlibs.ksp)
    implementation(serverlibs.kotlinCompiler)
    testImplementation(serverlibs.kotlinTest)
}

val lk = project.lk {
    version = gitBasedVersion().also { println("Determined version to be $it") }
}
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