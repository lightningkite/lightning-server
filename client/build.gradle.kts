import com.lightningkite.deployhelpers.*
import com.lightningkite.khrysalis.gradle.*

plugins {
    kotlin("jvm")
    id("org.jetbrains.dokka")
    id("signing")
    id("com.lightningkite.khrysalis")
    `maven-publish`
}

val kotlinVersion: String by project
val rxPlusVersion = "master-SNAPSHOT"
val khrysalisVersion: String by project
dependencies {
    kcp("com.lightningkite.khrysalis:kotlin-compiler-plugin-swift:$khrysalisVersion")
    kcp("com.lightningkite.khrysalis:kotlin-compiler-plugin-typescript:$khrysalisVersion")
    api(project(":shared"))
    api("com.lightningkite.khrysalis:jvm-runtime:$khrysalisVersion")
    api("com.lightningkite.rx:okhttp:$rxPlusVersion")
    api("com.lightningkite.rx:rxplus:$rxPlusVersion")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test:$kotlinVersion")
}

khrysalis {
    projectName = "KMongoClient"
    iosProjectFolder = rootDir.resolve("ios")
    iosSourceFolder = rootDir.resolve("ios/KMongoClient/Classes/client")
    libraryMode = true
}

standardPublishing {
    name.set("Ktor-Kmongo-Client")
    description.set("A tool for communication between a server and a client built around MongoDB Collections.")
    github("lightningkite", "ktor-kmongo")

    licenses {
        mit()
    }

    developers {
        developer(
            id = "LightningKiteJoseph",
            name = "Joseph Ivie",
            email = "joseph@lightningkite.com",
        )
        developer(
            id = "bjsvedin",
            name = "Brady Svedin",
            email = "brady@lightningkite.com",
        )
    }
}

