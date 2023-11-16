import com.lightningkite.deployhelpers.developer
import com.lightningkite.deployhelpers.github
import com.lightningkite.deployhelpers.mit
import com.lightningkite.deployhelpers.standardPublishing

plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp")
    kotlin("plugin.serialization")
    id("org.jetbrains.dokka")
    id("signing")
    `maven-publish`
}

val kotlinVersion:String by project
val coroutines:String by project
val kotlinXSerialization:String by project
dependencies {
    api(project(":server-core"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutines")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive:$coroutines")
    implementation("de.flapdoodle.embed:de.flapdoodle.embed.mongo:3.5.4")
    api("org.mongodb:mongodb-driver-kotlin-coroutine:4.10.1")

    kspTest(project(":processor"))
    testImplementation(project(":client"))
    testImplementation(project(":server-testing"))
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
}

ksp {
    arg("generateFields", "true")
}

kotlin {
    sourceSets.test {
        kotlin.srcDir("build/generated/ksp/test/kotlin")
    }
}

standardPublishing {
    name.set("Lightning-server-Mongo")
    description.set("An implementation of LightningServer Database using MongoDB.")
    github("lightningkite", "lightning-server")

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

