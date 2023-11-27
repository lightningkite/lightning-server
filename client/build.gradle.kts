import com.lightningkite.deployhelpers.*

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("org.jetbrains.dokka")
    id("signing")
    `maven-publish`
}

val kotlinVersion: String by project
val rxPlusVersion: String by project
val khrysalisVersion: String by project

kotlin {
    targetHierarchy.default()

    jvm()
    js(IR) {
        browser()
    }
//    ios()
//    androidTarget()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":shared"))
                api("com.lightningkite.rock:library:main-SNAPSHOT")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

standardPublishing {
    name.set("Lightning-server-Client")
    description.set("The client side of communication between server and client.")
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
