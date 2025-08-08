rootProject.name = "lightning-server"

pluginManagement {
    repositories {
        mavenLocal()
        google()
        gradlePluginPortal()
        mavenCentral()
        maven { url = uri("https://maven.pkg.jetbrains.space/public/p/kotlinx-html/maven") }
        maven { url = uri("https://maven.pkg.jetbrains.space/public/p/ktor/eap") }
        maven(url = "https://s01.oss.sonatype.org/content/repositories/snapshots/")
        maven(url = "https://s01.oss.sonatype.org/content/repositories/releases/")
    }

    plugins {
        kotlin("plugin.serialization") version "2.0.0"
        id("com.google.devtools.ksp") version "2.0.20-1.0.25"
    }

    dependencyResolutionManagement {
        repositories {
            mavenLocal()
            google()
            gradlePluginPortal()
            mavenCentral()
            maven("https://jitpack.io")
        }

    }
}

include(":core")
include(":engine-local")
include(":typed")
include(":auth")
include(":upload-files")
include(":ktor")
include(":aws")
include(":meta")