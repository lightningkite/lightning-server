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

        versionCatalogs {
            create("serverlibs") { from(files("gradle/serverlibs.versions.toml"))}
        }
    }
}

include(":demo")
include(":processor")
include(":shared")
include(":server")
include(":server-aws")
include(":server-azure")
//include(":server-cassandra")
include(":server-clamav")
include(":server-core")
include(":server-testing")
include(":server-dynamodb")
include(":server-firebase")
include(":server-ktor")
include(":server-memcached")
include(":server-mongo")
include(":server-postgresql")
include(":server-redis")
include(":server-sentry")
include(":server-sentry9")
include(":server-sftp")

