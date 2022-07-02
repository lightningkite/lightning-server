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

repositories {
    mavenLocal()
    maven(url = "https://s01.oss.sonatype.org/content/repositories/snapshots/")
    maven(url = "https://s01.oss.sonatype.org/content/repositories/releases/")
    maven { url = uri("https://maven.pkg.jetbrains.space/public/p/kotlinx-html/maven") }
    maven { url = uri("https://maven.pkg.jetbrains.space/public/p/ktor/eap") }
    mavenCentral()
}

val ktorVersion = "2.0.2"
val kotlinVersion: String by project
val khrysalisVersion: String by project
dependencies {

    // Security
    implementation("com.google.protobuf:protobuf-java:3.21.1")
    implementation("io.netty:netty-codec-http:4.1.77.Final")
    implementation("io.netty:netty-common:4.1.77.Final")
    implementation("com.google.oauth-client:google-oauth-client:1.34.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.13.3")
    // End Security

    api(project(":db"))
    api(project(":mongo"))

    api("org.signal:embedded-redis:0.8.3")


    api("io.sentry:sentry:1.7.30")
    api("io.sentry:sentry-logback:1.7.30")
    implementation("ch.qos.logback:logback-classic:1.2.11")

    api("com.lightningkite:kotliner-cli:1.0.3")
    implementation("com.lightningkite.khrysalis:jvm-runtime:$khrysalisVersion")

    api("io.ktor:ktor-server-auth-jvm:$ktorVersion")
    api("io.ktor:ktor-server-websockets-jvm:$ktorVersion")
    api("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    api("io.ktor:ktor-server-core-jvm:$ktorVersion")
    api("io.ktor:ktor-server-auth-jwt-jvm:$ktorVersion")
    api("io.ktor:ktor-server-cio-jvm:$ktorVersion")
    api("io.ktor:ktor-server-cors:$ktorVersion")
    api("io.ktor:ktor-server-status-pages:$ktorVersion")

    api("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    api("io.ktor:ktor-client-cio:$ktorVersion")

    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-html-builder-jvm:$ktorVersion")

    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive:1.6.2")
    implementation("de.flapdoodle.embed:de.flapdoodle.embed.mongo:3.4.6")
    implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:0.7.5")

    api("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    api("io.ktor:ktor-serialization-kotlinx-cbor:$ktorVersion")
    api("de.brudaswen.kotlinx.serialization:kotlinx-serialization-csv:2.0.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-properties:1.3.3")
    api("io.github.pdvrieze.xmlutil:serialization-jvm:0.84.2")

    api("org.apache.commons:commons-email:1.5")
    api("org.apache.commons:commons-vfs2:2.9.0")
    api("com.github.abashev:vfs-s3:4.3.5")
    api("com.azure:azure-storage-blob:12.17.1")
    api("com.github.dalet-oss:vfs-azure:4.2.1")
    api("com.charleskorn.kaml:kaml:0.45.0")
    api("com.google.firebase:firebase-admin:8.2.0")

    implementation("org.bouncycastle:bcprov-jdk18on:1.71")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.71")

    api("io.lettuce:lettuce-core:6.1.8.RELEASE")

    testImplementation("io.ktor:ktor-server-auth-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-server-auth-jwt-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-server-cio-jvm:$ktorVersion")

    testImplementation("org.jetbrains.kotlin:kotlin-test:$kotlinVersion")

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
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.freeCompilerArgs += "-opt-in=kotlinx.serialization.ExperimentalSerializationApi"
    kotlinOptions.freeCompilerArgs += "-opt-in=kotlin.RequiresOptIn"
}


standardPublishing {
    name.set("Ktor-Batteries-Server")
    description.set("A set of tools to fill in/replace what Ktor is lacking in.")
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