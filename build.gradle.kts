

buildscript {
    val kotlinVersion:String by extra
    val khrysalisVersion: String by extra
    repositories {
        mavenLocal()
        mavenCentral()
//        maven(url = "https://s01.oss.sonatype.org/content/repositories/snapshots/")
        maven(url = "https://s01.oss.sonatype.org/content/repositories/releases/")
        google()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
        classpath("org.jetbrains.dokka:dokka-gradle-plugin:1.6.20")
        classpath("com.lightningkite:deploy-helpers:0.0.5")
        classpath("com.lightningkite.khrysalis:plugin:$khrysalisVersion")
    }
}
allprojects {
    group = "com.lightningkite.ktorbatteries"
    repositories {
        mavenLocal()
        mavenCentral()
        maven(url = "https://s01.oss.sonatype.org/content/repositories/releases/")
//        maven(url = "https://s01.oss.sonatype.org/content/repositories/snapshots/")
    }
}
tasks.create("clean", Delete::class.java) {
    delete(rootProject.buildDir)
}