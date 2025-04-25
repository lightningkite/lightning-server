
import com.lightningkite.deployhelpers.github
import com.lightningkite.deployhelpers.mit
import com.lightningkite.deployhelpers.*
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.serialization)
    // alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

val lk = project.lk {
    version = gitBasedVersion().also { println("Determined version to be $it") }
}

dependencies {
    api(project(":shared"))
    api(libs.ktorJson)
    api(libs.ktorCioJvm)
    api(libs.ktorClientCio)
    api(libs.ktorContentNegotiation)
    api(lk.mavenOrLocal(
        gitUrl = "git@github.com:lightningkite/kotlinx-serialization-csv-durable.git",
        group = "com.lightningkite",
        artifact = "kotlinx-serialization-csv-durable",
        major = 0,
        minor = 2
    ))
    implementation(libs.coroutinesCore)
    implementation(libs.logBackClassic)
    implementation(libs.kotlinStdLib)
    implementation(libs.coroutinesCore)
    api(libs.kotlinHtmlJvm)
    api(libs.oneTimePass)
    api(libs.serializationCbor)
    api(libs.xmlUtilJvm)
    api(libs.mongoBson)
    api(libs.kBson)
    api(libs.kaml)
    api(libs.serializationProtobuf)
    api(libs.kotlinReflect)
    implementation(libs.bouncyCastleBcprov)
    implementation(libs.bouncyCastleBcpkix)

    api(libs.angusMail)
    testImplementation(libs.javaJwt)
    testImplementation(libs.kotlinTest)

    ksp(project(":processor"))
    kspTest(project(":processor"))
}

ksp {
    arg("generateFields", "true")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
    }
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


mavenPublishing {
    // publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates(group.toString(), name, version.toString())
    pom {
    name.set("Lightning-server-Server")
    description.set("A set of tools to fill in/replace what Ktor is lacking in.")
    github("lightningkite", "lightning-server")

    licenses {
        mit()
    }

    developers {
        joseph()
        brady()
    }
}

}
