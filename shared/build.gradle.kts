import com.lightningkite.deployhelpers.*
import com.lightningkite.khrysalis.gradle.*

plugins {
    kotlin("multiplatform")
    id("com.google.devtools.ksp")
    kotlin("plugin.serialization")
    id("com.lightningkite.khrysalis")
    id("org.jetbrains.dokka")
    id("signing")
    `maven-publish`
}

val kotlinVersion: String by project
val khrysalisVersion: String by project
val kotlinXSerialization: String by project

ksp {
    arg("generateFields", "true")
}

kotlin {
    targetHierarchy.default()

    jvm()
    js(IR) {
        browser()
    }
    ios()
//    androidTarget()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinXSerialization")
                api("org.jetbrains.kotlinx:kotlinx-serialization-properties:$kotlinXSerialization")

                implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
                implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.4.1")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmMain by getting {
            dependencies {
                api("com.lightningkite.khrysalis:jvm-runtime:$khrysalisVersion")
            }
        }
        val jvmTest by getting {
            dependsOn(commonTest)
        }
    }
}

dependencies {
    kcp("com.lightningkite.khrysalis:kotlin-compiler-plugin-swift:$khrysalisVersion")
    kcp("com.lightningkite.khrysalis:kotlin-compiler-plugin-typescript:$khrysalisVersion")

    equivalents("com.lightningkite.khrysalis:jvm-runtime:$khrysalisVersion:equivalents")

    configurations.filter { it.name.startsWith("ksp") && it.name != "ksp" }.forEach {
        add(it.name, project(":processor"))
    }
}

khrysalis {
    iosProjectName = "LightningServer"
    iosProjectFolder = rootDir.resolve("ios")
    iosSourceFolder = rootDir.resolve("ios/LightningServer/Classes/shared")
    webProjectName = "@lightningkite/lightning-server"
    webProjectFolder = rootDir.resolve("web")
    webSourceFolder = rootDir.resolve("web/src")
    libraryMode = true
    additionalEquivalentDirectories = listOf(project.file("build/generated/ksp/main/resources"))
}

tasks.getByName("equivalentsJar").dependsOn("kspKotlinJvm")
tasks.create("kspAll") {
    tasks.filter { it.name.startsWith("ksp") && it != this }.forEach {
        dependsOn(it)
    }
}

standardPublishing {
    name.set("Lightning-server-Shared")
    description.set("A tool for communication between a server and a client built around Ktor servers.")
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
tasks.getByName("equivalentsJar").published = true
