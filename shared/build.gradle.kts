import com.lightningkite.deployhelpers.*
import com.lightningkite.khrysalis.gradle.*

plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp")
    kotlin("plugin.serialization")
    id("com.lightningkite.khrysalis")
    id("org.jetbrains.dokka")
    id("signing")
    `maven-publish`
}

val kotlinVersion: String by project
val khrysalisVersion: String by project
dependencies {
    kcp("com.lightningkite.khrysalis:kotlin-compiler-plugin-swift:$khrysalisVersion")
    kcp("com.lightningkite.khrysalis:kotlin-compiler-plugin-typescript:$khrysalisVersion")

    equivalents("com.lightningkite.khrysalis:jvm-runtime:$khrysalisVersion:equivalents")

    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-properties:1.3.2")
    api("com.lightningkite.khrysalis:jvm-runtime:$khrysalisVersion")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")

    kspTest(project(":processor"))
    testImplementation("org.jetbrains.kotlin:kotlin-test:$kotlinVersion")
}

ksp {
    arg("generateFields", "true")
}

kotlin {
    sourceSets.test {
        kotlin.srcDir("build/generated/ksp/test/kotlin")
    }
}

khrysalis {
    iosProjectName = "BatteriesClient"
    iosProjectFolder = rootDir.resolve("ios")
    iosSourceFolder = rootDir.resolve("ios/BatteriesClient/Classes/shared")
    webProjectName = "@lightningkite/ktor-batteries"
    webProjectFolder = rootDir.resolve("web")
    webSourceFolder = rootDir.resolve("web/src")
    libraryMode = true
}

standardPublishing {
    name.set("Ktor-Batteries-Shared")
    description.set("A tool for communication between a server and a client built around Ktor servers.")
    github("lightningkite", "ktor-batteries")

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
tasks.getByName("equivalentsJarMain").published = true
