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

//    implementation("com.lightningkite.khrysalis:jvm-runtime:$khrysalisVersion")

    api("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")
    api("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    api("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutines")
    api("org.jetbrains.kotlinx:kotlinx-html-jvm:0.8.0")

    api("dev.turingcomplete:kotlin-onetimepassword:2.4.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-properties:$kotlinXSerialization")
    api("org.jetbrains.kotlinx:kotlinx-serialization-cbor:$kotlinXSerialization")
    api("io.github.pdvrieze.xmlutil:serialization-jvm:0.86.3")
    api("org.mongodb:bson:4.10.1")
    api("com.github.jershell:kbson:0.5.0")
    api("com.charleskorn.kaml:kaml:0.57.0")
    api(kotlin("reflect"))

    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.77")

    api("org.eclipse.angus:angus-mail:2.0.3")

    testImplementation("com.auth0:java-jwt:4.3.0")

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
