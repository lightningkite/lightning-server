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

repositories {
    mavenLocal()
    maven(url = "https://s01.oss.sonatype.org/content/repositories/snapshots/")
    maven(url = "https://s01.oss.sonatype.org/content/repositories/releases/")
    maven { url = uri("https://maven.pkg.jetbrains.space/public/p/kotlinx-html/maven") }
    maven { url = uri("https://maven.pkg.jetbrains.space/public/p/ktor/eap") }
    mavenCentral()
}

val kotlinVersion: String by project
val khrysalisVersion: String by project
dependencies {
    api(project(":server-core"))
    api("org.jetbrains.exposed:exposed-core:0.39.2")
    api("org.jetbrains.exposed:exposed-java-time:0.39.2")
    api("org.jetbrains.exposed:exposed-jdbc:0.39.2")
    api("org.postgresql:postgresql:42.5.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.6.4")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation(project(":server-testing"))
    testImplementation("io.zonky.test:embedded-postgres:2.0.3")
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