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

group = "com.lightningkite.ktor-batteries"

repositories {
//    mavenLocal()
//    maven(url = "https://s01.oss.sonatype.org/content/repositories/snapshots/")
    maven(url = "https://s01.oss.sonatype.org/content/repositories/releases/")
    maven { url = uri("https://maven.pkg.jetbrains.space/public/p/kotlinx-html/maven") }
    maven { url = uri("https://maven.pkg.jetbrains.space/public/p/ktor/eap") }
    mavenCentral()
}

val ktorVersion = "1.6.8"
val kotlinVersion: String by project
val ktorKmongoVersion = "0.10.0"
dependencies {
    api("com.lightningkite.ktorkmongo:server:$ktorKmongoVersion")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive:1.6.1")
    implementation("de.flapdoodle.embed:de.flapdoodle.embed.mongo:3.4.5")
    implementation("io.ktor:ktor-html-builder:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:0.7.5")

    api("org.jetbrains.kotlinx:kotlinx-serialization-properties:1.3.2")

    api("io.ktor:ktor-auth:$ktorVersion")
    api("io.ktor:ktor-websockets:$ktorVersion")
    api("io.ktor:ktor-serialization:$ktorVersion")
    api("io.ktor:ktor-server-core:$ktorVersion")
    api("io.ktor:ktor-auth-jwt:$ktorVersion")
    api("io.ktor:ktor-server-cio:$ktorVersion")

    api("org.apache.commons:commons-email:1.5")
    api("org.apache.commons:commons-vfs2:2.9.0")
    api("com.github.abashev:vfs-s3:4.3.5")
    api("com.charleskorn.kaml:kaml:0.43.0")
    api("com.lightningkite:kotliner-cli:1.0.3")
    api("com.google.firebase:firebase-admin:8.1.0")

    kspTest("com.lightningkite.ktorkmongo:processor:$ktorKmongoVersion")

    testImplementation("org.jetbrains.kotlin:kotlin-test:$kotlinVersion")
    testImplementation("io.ktor:ktor-auth:$ktorVersion")
    testImplementation("io.ktor:ktor-auth-jwt:$ktorVersion")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-serialization:$ktorVersion")
    testImplementation("io.ktor:ktor-server-cio:$ktorVersion")

    implementation("ch.qos.logback:logback-classic:1.2.11")
}

ksp {
    arg("generateFields", "true")
}

kotlin {
    sourceSets.test {
        kotlin.srcDir("build/generated/ksp/test/kotlin")
    }
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.freeCompilerArgs += "-opt-in=kotlinx.serialization.ExperimentalSerializationApi"
    kotlinOptions.freeCompilerArgs += "-opt-in=kotlin.RequiresOptIn"
}


standardPublishing {
    name.set("Ktor-Batteries")
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