
buildscript {
    repositories {
        mavenLocal()
        maven("https://lightningkite-maven.s3.us-west-2.amazonaws.com")
    }
    dependencies {
        classpath(libs.lkGradleHelpers)
        classpath(libs.proguard)
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

plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.serialization) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.androidApp) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.graalVmNative) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.vanniktechMavenPublish) apply false
    alias(libs.plugins.kover)
}

// by Claude - Kover configured in individual JVM modules (core, auth, typed, sessions, sessions-email, sessions-sms)
// Run ./gradlew :core:koverHtmlReport :auth:koverHtmlReport :typed:koverHtmlReport :sessions:koverHtmlReport for coverage
