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
include(":core-shared")

include(":typed")
include(":typed-shared")

include(":auth")
include(":auth-shared")

include(":files")
include(":files-shared")

include(":media")
include(":media-shared")

include(":sessions")
include(":sessions-email")
//include(":sessions-oauth")
//include(":sessions-oauth-shared")
include(":sessions-shared")
include(":sessions-sms")

include(":engine-local")
include(":engine-ktor")
include(":engine-aws-serverless")
include(":engine-netty")
include(":engine-jdk-server")

include(":secret-source-aws")

include(":demo")
