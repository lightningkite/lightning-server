
import com.lightningkite.deployhelpers.github
import com.lightningkite.deployhelpers.mit
import com.lightningkite.deployhelpers.*
import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.serialization)
    // alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(":server-aws"))
    api(project(":server-azure"))
    api(project(":server-core"))
    api(project(":server-ktor"))
    api(project(":server-mongo"))
    api(project(":server-memcached"))
    api(project(":server-redis"))
    api(project(":server-dynamodb"))
    api(project(":server-postgresql"))
    api(project(":server-sentry"))
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
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}

mavenPublishing {
    // publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates(group.toString(), name, version.toString())
    pom {
        name.set("Lightning-server-Server")
        description.set("A set of tools to fill in/replace what Ktor is lacking in.")
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