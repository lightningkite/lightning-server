import com.lightningkite.deployhelpers.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}


kotlin {
    explicitApi()
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        freeCompilerArgs.add("-Xcontext-parameters")
    }

    applyDefaultHierarchyTemplate()
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }

    jvm {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions{
                    jvmTarget = JvmTarget.JVM_1_8
                }
            }
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
                api(libs.kotlinx.json)
                api(libs.kotlinx.datetime)
                api(libs.services.database)
            }
            kotlin {
                srcDir(file("build/generated/ksp/common/commonMain/kotlin"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.serialization.protobuf)
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

        }
    }
}

dependencies {
    configurations.filter { it.name.startsWith("ksp") }.forEach {
        add(it.name, libs.services.database.processor)
    }
}

lkLibrary("lightningkite", "lightning-server") {
    description.set("A set of tools to fill in/replace what Ktor is lacking in.")
}

android {
    namespace = "com.lightningkite.lightningserver"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    dependencies {
        coreLibraryDesugaring(libs.androidDesugaring)
    }
}