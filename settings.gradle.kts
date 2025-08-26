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

include(":core")
include(":engine-local")
include(":auth")
include(":typed")
include(":sessions")
include(":sessions-email")
include(":sessions-sms")
//include(":sessions-oauth")
//include(":sessions-oauth-shared")
include(":sessions-shared")
include(":sdk")
include(":upload-files")
include(":ktor")
include(":aws")
include(":meta")
include(":vertx")
include(":demo")
include(":aws-serverless")