plugins {
    // alias(serverlibs.plugins.dokka) apply false
    alias(serverlibs.plugins.kotlinJvm) apply false
    alias(serverlibs.plugins.kotlinMultiplatform) apply false
    alias(serverlibs.plugins.androidApp) apply false
    alias(serverlibs.plugins.androidLibrary) apply false
    alias(serverlibs.plugins.graalVmNative) apply false
    alias(serverlibs.plugins.shadow) apply false
}

buildscript {
    repositories {
        mavenLocal()
        maven("https://lightningkite-maven.s3.us-west-2.amazonaws.com")
    }
    dependencies {
        classpath("com.lightningkite:lk-gradle-helpers:main-SNAPSHOT")
        classpath(serverlibs.proguard)
    }
}

allprojects {
    group = "com.lightningkite.lightningserver"
    repositories {
        mavenLocal()
        maven("https://lightningkite-maven.s3.us-west-2.amazonaws.com")
        google()
        mavenCentral()

    }
}