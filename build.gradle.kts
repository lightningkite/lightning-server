import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import com.sun.xml.fastinfoset.sax.Properties
import org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension


buildscript {
    val kotlinVersion:String by extra
    repositories {
//        mavenLocal()
//        maven(url = "https://s01.oss.sonatype.org/content/repositories/snapshots/")
        maven(url = "https://s01.oss.sonatype.org/content/repositories/releases/")
        google()
        gradlePluginPortal()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
        classpath("org.jetbrains.dokka:dokka-gradle-plugin:1.9.10")
        classpath("com.lightningkite:deploy-helpers:0.0.7")
        classpath("com.android.tools.build:gradle:7.4.2")
        classpath("org.owasp:dependency-check-gradle:9.0.9")
        classpath("com.github.ben-manes:gradle-versions-plugin:0.51.0")
    }
}
val localProps = project.rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { stream ->
    java.util.Properties().apply { load(stream) }
}
allprojects {
    apply(plugin = "org.owasp.dependencycheck")
    apply(plugin = "com.github.ben-manes.versions")
    configure<DependencyCheckExtension> {
        localProps?.getProperty("nvdApiKey")?.let {
            nvd.apiKey = it
        }
    }
    group = "com.lightningkite.lightningserver"
    repositories {
        mavenLocal()
//        maven(url = "https://s01.oss.sonatype.org/content/repositories/snapshots/")
        maven(url = "https://s01.oss.sonatype.org/content/repositories/releases/")
        google()
        mavenCentral()

    }
    tasks.withType<Jar> { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
    tasks.withType<DependencyUpdatesTask> {
        rejectVersionIf {
            isNonStable(candidate.version) && !isNonStable(currentVersion)
        }
    }
}
apply(plugin = "org.owasp.dependencycheck")
tasks.withType<DependencyUpdatesTask> {
    rejectVersionIf {
        isNonStable(candidate.version) && !isNonStable(currentVersion)
    }
}
fun isNonStable(version: String): Boolean {
    val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.uppercase().contains(it) }
    val regex = "^[0-9,.v-]+(-r)?$".toRegex()
    val isStable = stableKeyword || regex.matches(version)
    return isStable.not()
}