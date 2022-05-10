import com.lightningkite.deployhelpers.developer
import com.lightningkite.deployhelpers.github
import com.lightningkite.deployhelpers.mit
import com.lightningkite.deployhelpers.standardPublishing
import java.util.Properties

plugins {
    kotlin("jvm")
    id("org.jetbrains.dokka")
    id("signing")
    `maven-publish`
}


val kotlinVersion:String by project
val kspVersion:String by project
dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-api:$kspVersion")
    implementation("org.jetbrains.kotlin:kotlin-compiler:$kotlinVersion")
}

standardPublishing {
    name.set("Ktor-Kmongo-Processor")
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

