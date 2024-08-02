import com.lightningkite.deployhelpers.*

plugins {
    alias(serverlibs.plugins.kotlinJvm)
    alias(serverlibs.plugins.dokka)
    id("signing")
    `maven-publish`
}


val kotlinVersion:String by project
val kspVersion:String by project

dependencies {
    implementation(serverlibs.ksp)
    implementation(serverlibs.kotlinCompiler)
    testImplementation(serverlibs.kotlinTest)
}

standardPublishing {
    name.set("Lightning-server-Processor")
    description.set("A tool for communication between a server and a client built around Ktor Servers")
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
