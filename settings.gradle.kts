rootProject.name = "lightning-server"

pluginManagement {
    val kotlinVersion: String by settings
    val kspVersion: String by settings

    plugins {
        kotlin("plugin.serialization") version kotlinVersion
        id("com.google.devtools.ksp") version kspVersion
    }
}

include(":client")
include(":demo")
include(":processor")
include(":shared")
include(":server")
include(":server-aws")
include(":server-azure")
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
