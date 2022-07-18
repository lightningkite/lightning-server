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
include(":server-firebase")
include(":server-ktor")
include(":server-mongo")
include(":server-redis")
include(":server-sentry")
