import com.lightningkite.deployhelpers.developer
import com.lightningkite.deployhelpers.github
import com.lightningkite.deployhelpers.mit
import com.lightningkite.deployhelpers.standardPublishing
import com.strumenta.antlrkotlin.gradle.*
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(serverlibs.plugins.kotlinJvm)
    alias(serverlibs.plugins.ksp)
    alias(serverlibs.plugins.serialization)
    alias(serverlibs.plugins.dokka)
    id("com.strumenta.antlr-kotlin") version "1.0.1"
    id("signing")
    `maven-publish`
}

dependencies {
    api(project(":server-core"))
    implementation(serverlibs.coroutinesReactive)
    implementation("com.strumenta:antlr-kotlin-runtime:1.0.1")
    testImplementation(serverlibs.kotlinTest)
    testImplementation(project(":server-testing"))
    ksp(project(":processor"))
    kspTest(project(":processor"))
}

ksp {
    arg("generateFields", "true")
}

kotlin {
    sourceSets.main {
        kotlin.srcDir("build/generated/ksp/main/kotlin")
        kotlin.srcDir(layout.buildDirectory.dir("generatedAntlr"))
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
    description.set("An implementation of LightningServer FileSystem using sftp.")
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

val generateKotlinGrammarSource = tasks.register<AntlrKotlinTask>("generateKotlinGrammarSource") {
    dependsOn("cleanGenerateKotlinGrammarSource")

    // ANTLR .g4 files are under {example-project}/antlr
    // Only include *.g4 files. This allows tools (e.g., IDE plugins)
    // to generate temporary files inside the base path
    source = fileTree(layout.projectDirectory.dir("src/main/antlr")) {
        include("**/*.g4")
    }

    // We want the generated source files to have this package name
    val pkgName = "com.lightningkite.lightningserver.scim.parse"
    packageName = pkgName

    // We want visitors alongside listeners.
    // The Kotlin target language is implicit, as is the file encoding (UTF-8)
    arguments = listOf("-visitor")

    // Generated files are outputted inside build/generatedAntlr/{package-name}
    val outDir = "generatedAntlr/${pkgName.replace(".", "/")}"
    outputDirectory = layout.buildDirectory.dir(outDir).get().asFile
}
tasks.withType<KotlinCompile> {
    dependsOn(generateKotlinGrammarSource)
}