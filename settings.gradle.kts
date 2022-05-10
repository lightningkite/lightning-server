rootProject.name = "ktor-batteries"


pluginManagement {
    val kotlinVersion: String by settings
    val kspVersion: String by settings

    plugins {
        kotlin("plugin.serialization") version kotlinVersion
        id("com.google.devtools.ksp") version kspVersion
    }
}

include(":client")
include(":db")
include(":demo")
include(":mongo")
include(":processor")
include(":shared")
include(":server")
