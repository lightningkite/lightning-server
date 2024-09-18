

buildscript {
    val kotlinVersion:String by extra
    val khrysalisVersion: String by extra
    repositories {
//        mavenLocal()
        maven(url = "https://s01.oss.sonatype.org/content/repositories/snapshots/")
        maven(url = "https://s01.oss.sonatype.org/content/repositories/releases/")
        google()
        mavenCentral()
    }

    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
        classpath("org.jetbrains.dokka:dokka-gradle-plugin:1.7.10")
        classpath("com.lightningkite:deploy-helpers:master-SNAPSHOT")
        classpath("com.lightningkite.khrysalis:plugin:$khrysalisVersion")
    }
}
allprojects {
    group = "com.lightningkite.lightningserver"
    repositories {
//        mavenLocal()
        maven(url = "https://s01.oss.sonatype.org/content/repositories/snapshots/")
        maven(url = "https://s01.oss.sonatype.org/content/repositories/releases/")
        mavenCentral()

    }
    tasks.withType<Jar> { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
}
tasks.create("clean", Delete::class.java) {
    delete(rootProject.buildDir)
}