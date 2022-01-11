import java.util.Properties

plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp")
    kotlin("plugin.serialization")
    id("org.jetbrains.dokka")
    id("signing")
    `maven-publish`
}

val publishVersion:String by project
version = publishVersion + if(System.getenv("stage") == "true") "" else "-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
    maven { url = uri("https://maven.pkg.jetbrains.space/public/p/kotlinx-html/maven") }
    maven { url = uri("https://maven.pkg.jetbrains.space/public/p/ktor/eap") }
}

val ktorVersion = "1.6.7"
val kotlinVersion:String by project
dependencies {
    api("com.lightningkite.ktorkmongo:server:0.0.4-SNAPSHOT")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive:1.5.2")
    implementation("de.flapdoodle.embed:de.flapdoodle.embed.mongo:3.2.6")
    implementation("io.ktor:ktor-html-builder:$ktorVersion")

    api("io.ktor:ktor-auth:$ktorVersion")
    api("io.ktor:ktor-websockets:$ktorVersion")
    api("io.ktor:ktor-serialization:$ktorVersion")
    api("io.ktor:ktor-server-core:$ktorVersion")
    api("io.ktor:ktor-auth-jwt:$ktorVersion")
    api("io.ktor:ktor-server-cio:$ktorVersion")

    api("org.litote.kmongo:kmongo-coroutine-serialization:4.4.0")
    implementation("io.ktor:ktor-gson:1.6.7")

    implementation("org.apache.commons:commons-email:1.5")
    implementation("org.apache.commons:commons-vfs2:2.9.0")
    implementation("com.github.abashev:vfs-s3:4.3.5")

    implementation("com.charleskorn.kaml:kaml:0.38.0")
    implementation("com.lightningkite:kotliner-cli:1.0.1")

    implementation("com.google.firebase:firebase-admin:8.1.0")

    kspTest("com.lightningkite.ktorkmongo:processor:0.0.4-SNAPSHOT")

    testImplementation("org.jetbrains.kotlin:kotlin-test:$kotlinVersion")
    testImplementation("io.ktor:ktor-auth:$ktorVersion")
    testImplementation("io.ktor:ktor-auth-jwt:$ktorVersion")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-serialization:$ktorVersion")
    testImplementation("io.ktor:ktor-server-cio:$ktorVersion")
}

ksp {
    arg("generateFields", "true")
}

kotlin {
    sourceSets.test {
        kotlin.srcDir("build/generated/ksp/test/kotlin")
    }
}


// Signing and publishing
val props = project.rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { stream ->
    Properties().apply { load(stream) }
}
val signingKey: String? = (System.getenv("SIGNING_KEY")?.takeUnless { it.isEmpty() }
    ?: props?.getProperty("signingKey")?.toString())
    ?.lineSequence()
    ?.filter { it.trim().firstOrNull()?.let { it.isLetterOrDigit() || it == '=' || it == '/' || it == '+' } == true }
    ?.joinToString("\n")
val signingPassword: String? = System.getenv("SIGNING_PASSWORD")?.takeUnless { it.isEmpty() }
    ?: props?.getProperty("signingPassword")?.toString()
val useSigning = signingKey != null && signingPassword != null

if (signingKey != null) {
    if (!signingKey.contains('\n')) {
        throw IllegalArgumentException("Expected signing key to have multiple lines")
    }
    if (signingKey.contains('"')) {
        throw IllegalArgumentException("Signing key has quote outta nowhere")
    }
}

val deploymentUser = (System.getenv("OSSRH_USERNAME")?.takeUnless { it.isEmpty() }
    ?: props?.getProperty("ossrhUsername")?.toString())
    ?.trim()
val deploymentPassword = (System.getenv("OSSRH_PASSWORD")?.takeUnless { it.isEmpty() }
    ?: props?.getProperty("ossrhPassword")?.toString())
    ?.trim()
val useDeployment = deploymentUser != null || deploymentPassword != null

tasks {
    val sourceJar by creating(Jar::class) {
        archiveClassifier.set("sources")
        from(kotlin.sourceSets["main"].kotlin.srcDirs)
    }
    val javadocJar by creating(Jar::class) {
        dependsOn("dokkaJavadoc")
        archiveClassifier.set("javadoc")
        from(project.file("build/dokka/javadoc"))
    }
    artifacts {
        archives(sourceJar)
        archives(javadocJar)
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("maven") {
                from(components["kotlin"])
                artifact(tasks.getByName("sourceJar"))
                if (useSigning) {
                    artifact(tasks.getByName("javadocJar"))
                }
                setPom()
            }
        }
        if (useDeployment) {
            repositories {
                maven {
                    name = "MavenCentral"
                    val releasesRepoUrl = "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
                    val snapshotsRepoUrl = "https://s01.oss.sonatype.org/content/repositories/snapshots/"
                    url = uri(if(System.getenv("stage") == "true") releasesRepoUrl else snapshotsRepoUrl)
                    credentials {
                        this.username = deploymentUser
                        this.password = deploymentPassword
                    }
                }
            }
        }
    }
    if (useSigning) {
        signing {
            useInMemoryPgpKeys(signingKey, signingPassword)
            sign(publishing.publications)
        }
    }
}

fun MavenPublication.setPom() {
    pom {
        name.set("Ktor-Kmongo-Server")
        description.set("A tool for easy management of your MongoDB Collections in Ktor.")
        url.set("https://github.com/lightningkite/ktor-kmongo")

        scm {
            connection.set("scm:git:https://github.com/lightningkite/ktor-kmongo.git")
            developerConnection.set("scm:git:https://github.com/lightningkite/ktor-kmongo.git")
            url.set("https://github.com/lightningkite/ktor-kmongo")
        }

        licenses {
            license {
                name.set("The MIT License (MIT)")
                url.set("https://www.mit.edu/~amini/LICENSE.md")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("LightningKiteJoseph")
                name.set("Joseph Ivie")
                email.set("joseph@lightningkite.com")
            }
            developer {
                id.set("bjsvedin")
                name.set("Brady Svedin")
                email.set("brady@lightningkite.com")
            }
        }
    }
}
