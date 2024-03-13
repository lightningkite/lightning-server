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

val ktorVersion: String by project
val kotlinVersion: String by project
val logBack: String by project
val coroutines: String by project
val kotlinXSerialization: String by project
dependencies {
    api(project(":shared"))

    // Security
//    implementation("com.google.protobuf:protobuf-java:3.21.1")
//    implementation("io.netty:netty-codec-http:4.1.77.Final")
//    implementation("io.netty:netty-common:4.1.77.Final")
//    implementation("com.google.oauth-client:google-oauth-client:1.34.1")
//    implementation("com.fasterxml.jackson.core:jackson-databind:2.13.3")
    // End Security

    implementation("ch.qos.logback:logback-classic:$logBack")

    api("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")
    api("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    api("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutines")
    api(libs.kotlinx.html.jvm)

    api(libs.kotlin.onetimepassword)
    api(libs.kotlinx.serialization.csv)
    api("org.jetbrains.kotlinx:kotlinx-serialization-properties:$kotlinXSerialization")
    api("org.jetbrains.kotlinx:kotlinx-serialization-cbor:$kotlinXSerialization")
    api(libs.serialization.jvm)
    api(libs.bson)
    api(libs.kbson)
    api(libs.kaml)
    api(kotlin("reflect"))

    implementation(libs.bcprov.jdk18on)
    implementation(libs.bcpkix.jdk18on)

    api(libs.commons.email)

    testImplementation(libs.java.jwt)

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")

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
