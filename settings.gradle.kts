rootProject.name = "ktor-batteries"


pluginManagement {
    val kotlinVersion: String by settings
    val kspVersion: String by settings

    plugins {
        kotlin("plugin.serialization") version kotlinVersion
        id("com.google.devtools.ksp") version kspVersion
    }
}

buildscript {
    val kotlinVersion:String by extra
    val dokkaVersion:String by extra
    repositories {
        mavenLocal()
        mavenCentral()
        google()
        maven(url = "https://s01.oss.sonatype.org/content/repositories/snapshots/")
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
        classpath("org.jetbrains.dokka:dokka-gradle-plugin:$dokkaVersion")
        classpath("com.lightningkite:deploy-helpers:master-SNAPSHOT")
    }
}

