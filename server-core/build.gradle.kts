import com.lightningkite.deployhelpers.developer
import com.lightningkite.deployhelpers.github
import com.lightningkite.deployhelpers.mit
import com.lightningkite.deployhelpers.standardPublishing

plugins {
    alias(serverlibs.plugins.kotlinJvm)
    alias(serverlibs.plugins.ksp)
    alias(serverlibs.plugins.serialization)
    alias(serverlibs.plugins.dokka)
    id("signing")
    `maven-publish`
}

dependencies {
    api(project(":shared"))
    api(serverlibs.ktorJson)
    api(serverlibs.ktorCioJvm)
    api(serverlibs.ktorClientCio)
    api(serverlibs.ktorContentNegotiation)
    implementation(serverlibs.coroutinesCore)
    implementation(serverlibs.logBackClassic)
    implementation(serverlibs.kotlinStdLib)
    implementation(serverlibs.coroutinesCore)
    api(serverlibs.kotlinHtmlJvm)
    api(serverlibs.oneTimePass)
    api(serverlibs.serializationProperties)
    api(serverlibs.serializationCbor)
    api(serverlibs.xmlUtilJvm)
    api(serverlibs.mongoBson)
    api(serverlibs.kBson)
    api(serverlibs.kaml)
    api(serverlibs.serializationProtobuf)
    api(serverlibs.kotlinReflect)
    implementation(serverlibs.bouncyCastleBcprov)
    implementation(serverlibs.bouncyCastleBcpkix)

    api(serverlibs.angusMail)
    testImplementation(serverlibs.javaJwt)
    testImplementation(serverlibs.kotlinTest)

    ksp(project(":processor"))
    kspTest(project(":processor"))
}

ksp {
    arg("generateFields", "true")
}

kotlin {
    sourceSets.main {
        kotlin.srcDir("build/generated/ksp/main/kotlin")
    }
    sourceSets.test {
        kotlin.srcDir("build/generated/ksp/test/kotlin")
    }
}
tasks.withType<JavaCompile>().configureEach {
    this.targetCompatibility = "11"
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.freeCompilerArgs += "-opt-in=kotlinx.serialization.ExperimentalSerializationApi"
    kotlinOptions.freeCompilerArgs += "-opt-in=kotlin.RequiresOptIn"
    kotlinOptions.jvmTarget = JavaVersion.VERSION_11.toString()
}

standardPublishing {
    name.set("Lightning-server-Server")
    description.set("A set of tools to fill in/replace what Ktor is lacking in.")
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

tasks.getByName("sourceJar").dependsOn("kspKotlin")
