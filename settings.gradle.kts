//rootProject.name = "lightning-server"
//
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
include(":sessions-shared")
include(":sessions-email")
include(":sessions-oauth")
include(":sessions-oauth-shared")
include(":sessions-openid-provider")
include(":sessions-openid-provider-shared")
include(":sessions-sms")

include(":notifications")
include(":notifications-shared")

include(":engine-local")
include(":engine-ktor")
include(":engine-aws-serverless")
include(":engine-netty")
include(":engine-jdk-server")

include(":ratelimit")

include(":secret-source-aws")

include(":load-test")

include(":demo")
