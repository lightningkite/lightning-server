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
    implementation("de.flapdoodle.embed:de.flapdoodle.embed.mongo:3.4.9")
    implementation("com.github.jershell:kbson:0.4.5")
    api("org.litote.kmongo:kmongo-coroutine-serialization:4.7.1")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinXSerialization")

    kspTest(project(":processor"))
    testImplementation(project(":client"))
    testImplementation(project(":server-testing"))
    testImplementation("org.jetbrains.kotlin:kotlin-test:$kotlinVersion")
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
    description.set("A MongoDB implementation of Lightning-server-Databases.")
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

