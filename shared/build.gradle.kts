import com.lightningkite.deployhelpers.*

plugins {
    alias(serverlibs.plugins.kotlinMultiplatform)
    alias(serverlibs.plugins.ksp)
    alias(serverlibs.plugins.serialization)
    alias(serverlibs.plugins.androidLibrary)
    alias(serverlibs.plugins.dokka)
    id("signing")
    `maven-publish`
}

ksp {
    arg("generateFields", "true")
}

kotlin {
    applyDefaultHierarchyTemplate()
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
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

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(serverlibs.kotlinXJson)
                api(serverlibs.kotlinXProperties)
                api(serverlibs.kotlinXDatetime)

                implementation(serverlibs.kotlinReflect)
                implementation(serverlibs.kotlinStdLib)

            }
            kotlin {
                srcDir(file("build/generated/ksp/common/commonMain/kotlin"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
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

standardPublishing {
    name.set("Lightning-server-Shared")
    description.set("A tool for communication between a server using LightningServer and a client.")
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

android {
    namespace = "com.lightningkite.lightningserver"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    dependencies {
        coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    }
}