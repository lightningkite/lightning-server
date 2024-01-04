import com.lightningkite.deployhelpers.*

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("org.jetbrains.dokka")
    id("signing")
    `maven-publish`
}

val kotlinVersion: String by project
val rxPlusVersion: String by project
val khrysalisVersion: String by project

kotlin {
    targetHierarchy.default()

    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "1.8"
        }
    }
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
            kotlin {
                srcDir(file("build/generated/ksp/common/commonMain/kotlin"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
            kotlin {
                srcDir(file("build/generated/ksp/common/commonTest/kotlin"))
            }
        }
    }
}

android {
    namespace = "com.lightningkite.lightningserver"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
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
