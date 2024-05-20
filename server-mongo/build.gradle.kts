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
group = "com.lightningkite"
dependencies {
    api(project(":server-core"))
    implementation(serverlibs.kotlinStdLib)
    implementation(serverlibs.coroutinesCore)
    implementation(serverlibs.coroutinesReactive)
    implementation(serverlibs.embedMongo)
    implementation(serverlibs.mongoDriver)
    ksp(project(":processor"))
    kspTest(project(":processor"))
    testImplementation(project(":server-testing"))
    testImplementation(serverlibs.kotlinTest)
}

ksp {
    arg("generateFields", "true")
}

kotlin {
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
    name.set("Lightning-server-Mongo")
    description.set("An implementation of LightningServer Database using MongoDB.")
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