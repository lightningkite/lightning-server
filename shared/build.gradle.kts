import com.lightningkite.deployhelpers.*
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.ksp)
    alias(libs.plugins.serialization)
    alias(libs.plugins.androidLibrary)
    // alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

ksp {
    arg("generateFields", "true")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
    }
    applyDefaultHierarchyTemplate()
    androidTarget {
        compilations.all {
            kotlinOptions.jvmTarget = "1.8"
        }
    }

    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "1.8"
        }
    }
    js(IR) {
        browser()
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    macosX64()
    macosArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.kotlinXJson)
                api(libs.serializationProperties)
                api(libs.kotlinXDatetime)

                implementation(libs.kotlinReflect)
                implementation(libs.kotlinStdLib)

            }
            kotlin {
                srcDir(file("build/generated/ksp/common/commonMain/kotlin"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.serializationProtobuf)
            }
            kotlin {
                srcDir(file("build/generated/ksp/common/commonTest/kotlin"))
            }
        }
        val jvmMain by getting {
            dependencies {
            }
        }
        val jvmTest by getting {
            dependsOn(commonTest)
        }
    }
}

dependencies {
    configurations.filter { it.name.startsWith("ksp") && it.name != "ksp" }.forEach {
        add(it.name, project(":processor"))
    }
}

val lk = project.lk {
    version = gitBasedVersion().also { println("Determined version to be $it") }
}
mavenPublishing {
    // publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates(group.toString(), name, version.toString())
    pom {
        name.set("Lightning-server-Shared")
        description.set("A tool for communication between a server using LightningServer and a client.")
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

android {
    namespace = "com.lightningkite.lightningserver"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    dependencies {
        coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")
    }
}