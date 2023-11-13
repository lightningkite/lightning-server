import com.lightningkite.deployhelpers.*

plugins {
    kotlin("multiplatform")
    id("com.google.devtools.ksp")
    kotlin("plugin.serialization")
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
            }
        }
        val jvmTest by getting {
            dependsOn(commonTest)
        }
    }
}

dependencies {
    configurations.filter { it.name.startsWith("ksp") && it.name != "ksp" }.forEach {
        add(it.name, project(":processor"))
    }
}

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
