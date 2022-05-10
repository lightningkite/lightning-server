rootProject.name = "ktor-batteries"


pluginManagement {
    val kotlinVersion: String by settings
    val kspVersion: String by settings

    plugins {
        kotlin("plugin.serialization") version kotlinVersion
        id("com.google.devtools.ksp") version kspVersion
    }
}

include(":server")
include(":demo")
include(":mongo")
include(":processor")
include(":db")
include(":shared")
include(":client")
