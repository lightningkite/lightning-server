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
val rxPlusVersion = "1.0.3"
val khrysalisVersion: String by project
dependencies {
    api(project(":shared"))
    api("com.lightningkite.khrysalis:jvm-runtime:$khrysalisVersion")
    api("com.lightningkite.rx:okhttp:$rxPlusVersion")
    api("com.lightningkite.rx:rxplus:$rxPlusVersion")

    equivalents("com.lightningkite.rx:rxplus:$rxPlusVersion:equivalents")
    equivalents("com.lightningkite.khrysalis:jvm-runtime:$khrysalisVersion:equivalents")

    kcp("com.lightningkite.khrysalis:kotlin-compiler-plugin-swift:$khrysalisVersion")
    kcp("com.lightningkite.khrysalis:kotlin-compiler-plugin-typescript:$khrysalisVersion")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test:$kotlinVersion")
}

khrysalis {
    iosProjectName = "KtorBatteries"
    iosProjectFolder = rootDir.resolve("ios")
    iosSourceFolder = rootDir.resolve("ios/KtorBatteries/Classes/client")
    webProjectName = "@lightningkite/ktor-batteries"
    webProjectFolder = rootDir.resolve("web")
    webSourceFolder = rootDir.resolve("web/src")
    libraryMode = true
}

standardPublishing {
    name.set("Ktor-Batteries-Client")
    description.set("The client side of communication between server and client.")
    github("lightningkite", "ktor-batteries")

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
tasks.getByName("equivalentsJarMain").published = true

