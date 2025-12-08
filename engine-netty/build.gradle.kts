import com.lightningkite.deployhelpers.*

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.serialization)
    alias(libs.plugins.dokka)
    id("signing")
    alias(libs.plugins.vanniktechMavenPublish)
}

dependencies {
    api(project(":core"))
    api(project(":engine-local"))
    api(libs.serviceAbstractionsDatabase)
    api(libs.serviceAbstractionsCache)
    api(libs.serviceAbstractionsPubsub)
    api(libs.kotlinReflect)

    implementation(platform(libs.nettyBom))
    implementation(libs.nettyCodecHttp)
    implementation(libs.nettyHandler)
    implementation(libs.nettyTransport)
    implementation(libs.nettyBuffer)
    implementation(libs.nettyCodec)

    // For native transports (compile-time APIs); actual native libs load if present on the platform
    implementation(libs.nettyTransportClassesEpoll)
    implementation(libs.nettyTransportClassesKqueue)

    testImplementation(libs.kotlinTest)
    testImplementation(libs.kotlinTestJunit)
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
}

ksp {
    arg("generateFields", "true")
}

kotlin {
    explicitApi()
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        freeCompilerArgs.add("-Xcontext-parameters")
    }
    sourceSets.main {
        kotlin.srcDir("build/generated/ksp/main/kotlin")
    }
    sourceSets.test {
        kotlin.srcDir("build/generated/ksp/test/kotlin")
    }
}

lkLibrary("lightningkite", "lightning-server") {
    description.set("A Netty engine implementation for Lightning Server.")
}