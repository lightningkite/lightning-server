
import com.lightningkite.deployhelpers.github
import com.lightningkite.deployhelpers.mit
import com.lightningkite.deployhelpers.*
import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.serialization)
    // alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(":shared"))
    api(libs.ktorJson)
    api(libs.ktorCioJvm)
    api(libs.ktorClientCio)
    api(libs.ktorContentNegotiation)
    api(libs.comLightningkiteKotlinxSerializationCsvDurable)
    implementation(libs.coroutinesCore)
    implementation(libs.logBackClassic)
    implementation(libs.metadataExtractor)
    implementation(libs.kotlinStdLib)
    implementation(libs.coroutinesCore)
    api(libs.kotlinHtmlJvm)
    api(libs.oneTimePass)
    api(libs.serializationCbor)
    api(libs.xmlUtilJvm)
    api(libs.mongoBson)
//    api(libs.kBson)
    api(libs.kaml)
    api(libs.serializationProtobuf)
    api(libs.kotlinReflect)
    implementation(libs.bouncyCastleBcprov)
    implementation(libs.bouncyCastleBcpkix)
    implementation(libs.webauthn4jCore)

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
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
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
