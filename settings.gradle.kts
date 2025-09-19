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
}

include(":auth")
include(":auth-shared")
include(":aws")
include(":aws-serverless")
include(":core")
include(":core-shared")
include(":demo")
include(":engine-local")
include(":ktor")
include(":meta")
include(":files")
include(":files-shared")
include(":sessions")
include(":sessions-email")
//include(":sessions-oauth")
//include(":sessions-oauth-shared")
include(":sessions-shared")
include(":sessions-sms")
include(":typed")
include(":typed-shared")
include(":upload-files")
include(":vertx")
include(":jdk-server")
include(":netty")
include(":media")
include(":media-shared")