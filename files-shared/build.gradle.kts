import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}


kotlin {
    explicitApi()
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }

    applyDefaultHierarchyTemplate()
    android {
        namespace = "com.lightningkite.lightningserver.files.shared"
        compileSdk = 37
        minSdk = 21
        enableCoreLibraryDesugaring = true
        withHostTest {}
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }

    jvm {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget = JvmTarget.JVM_1_8
                }
            }
        }
    }
    js{ browser() }
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    macosArm64()

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                api(project(":typed-shared"))
                api(libs.kotlinx.datetime)
                api(sa.files.client)
                api(sa.database.shared)
            }
        }
        val commonTest = getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
        val jvmMain = getByName("jvmMain") {}
        val jvmTest = getByName("jvmTest") {}
    }
}

dependencies {
    coreLibraryDesugaring(libs.androidDesugaring)
}

mavenPublishing {
    pom { description.set("A set of models shared between the server and client for managing PublicFileSystem files.") }
}
