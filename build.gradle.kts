plugins {
    alias(serverlibs.plugins.dokka) apply false
    alias(serverlibs.plugins.kotlinJvm) apply false
    alias(serverlibs.plugins.kotlinMultiplatform) apply false
    alias(serverlibs.plugins.androidApp) apply false
    alias(serverlibs.plugins.androidLibrary) apply false
    alias(serverlibs.plugins.graalVmNative) apply false
    alias(serverlibs.plugins.shadow) apply false
}

buildscript {
    dependencies {
        classpath(serverlibs.deployHelpers)
        classpath(serverlibs.proguard)
    }
}