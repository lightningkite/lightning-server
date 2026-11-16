import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

buildscript {
    repositories {
        mavenLocal()
        maven("https://lightningkite-maven.s3.us-west-2.amazonaws.com")
        mavenCentral()
    }
    dependencies {
        classpath(libs.proguard)
        // dependency-check 13.0.0 asks for 5.6.3; the AWS SDK that lk-gradle-helpers used to put here raised it to 5.6.4.
        classpath(libs.httpclient5)
    }
}

allprojects {
    repositories {
        mavenLocal()
        maven("https://lightningkite-maven.s3.us-west-2.amazonaws.com")
        google()
        mavenCentral()

    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.androidApp) apply false
    alias(libs.plugins.androidKmpLibrary) apply false
    alias(libs.plugins.graalVmNative) apply false
    alias(libs.plugins.vanniktechMavenPublish) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.dependencyCheck)
    `version-catalog`
    `maven-publish`
}

// Group and version come from each published module's gradle.properties.  `lk` (in metawork) is the only thing that
// edits the versions.
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral(automaticRelease = false)
            signAllPublications()
            // Dokka is slow, so the javadoc jar is built only on request.
            configureBasedOnAppliedPlugins(sourcesJar = true, javadocJar = providers.gradleProperty("withDocs").isPresent)
            pom {
                name.set(project.name)
                url.set("https://lightningkite-maven.s3.amazonaws.com/${group.toString().replace('.', '/')}/${project.name}/$version/docs/index.html")
                scm {
                    connection.set("scm:git:https://github.com/lightningkite/lightning-server.git")
                    developerConnection.set("scm:git:https://github.com/lightningkite/lightning-server.git")
                    url.set("https://github.com/lightningkite/lightning-server")
                }
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer { id.set("LightningKiteJoseph"); name.set("Joseph Ivie"); email.set("joseph@lightningkite.com") }
                    developer { id.set("bjsvedin"); name.set("Brady Svedin"); email.set("brady@lightningkite.com") }
                    developer { id.set("iHoonter"); name.set("Hunter"); email.set("Hunter@lightningkite.com") }
                }
            }
        }

        extensions.configure<SigningExtension> {
            val signingKey = providers.gradleProperty("signingKey").orNull
            if (signingKey != null) useInMemoryPgpKeys(signingKey, providers.gradleProperty("signingPassword").orNull)
        }
        tasks.withType<Sign>().configureEach {
            val hasKey = providers.gradleProperty("signingKey").isPresent
            onlyIf("a signing key is configured") { hasKey }
        }

        plugins.withId("org.jetbrains.dokka") {
            dependencies.add("dokkaPlugin", "com.lightningkite:dokka-plugin-hide-optin:0.0.4")
            extensions.configure<DokkaExtension> {
                dokkaSourceSets.configureEach {
                    sourceRoots.map { it.resolve("module.md") }.firstOrNull { it.exists() }?.let { includes.from(it) }
                    sourceLink {
                        localDirectory.set(file("src/$name/kotlin"))
                        remoteUrl.set(uri("https://github.com/lightningkite/lightning-server/tree/master/"))
                        remoteLineSuffix.set("#L")
                    }
                }
            }
        }

        extensions.configure<PublishingExtension> {
            repositories {
                maven { name = "staging"; url = uri(rootProject.layout.buildDirectory.dir("staging-repo")) }
            }
        }

        // `lk` compares a fresh dump with the last release's to choose the next version, so no dumps are committed or checked.
        plugins.withId("org.jetbrains.kotlin.jvm") {
            extensions.configure<KotlinJvmProjectExtension> {
                @OptIn(ExperimentalAbiValidation::class)
                abiValidation()
            }
        }
        plugins.withId("org.jetbrains.kotlin.multiplatform") {
            extensions.configure<KotlinMultiplatformExtension> {
                @OptIn(ExperimentalAbiValidation::class)
                abiValidation()
            }
        }
        tasks.matching { it.name == "checkKotlinAbi" }.configureEach { enabled = false }
    }
}

// The catalog downstream repos pin: this repo's modules at the versions being published.
val published = subprojects.filter { it.file("gradle.properties").let { f -> f.isFile && f.readText().contains(Regex("(?m)^version=")) } }
catalog { versionCatalog { published.forEach { library(it.name, "${it.group}:${it.name}:${it.version}") } } }
publishing {
    publications.create<MavenPublication>("catalog") {
        from(components["versionCatalog"])
        groupId = "com.lightningkite.stack"
        artifactId = "lightning-server-catalog"
        version = providers.gradleProperty("catalogVersion").getOrElse("unspecified")
    }
    repositories { maven { name = "staging"; url = uri(layout.buildDirectory.dir("staging-repo")) } }
}

// ---------------------------------------------------------------------------
// CVE scanning: OWASP dependency-check (WARN-ONLY, never gates, by Claude)
// ---------------------------------------------------------------------------
// The reviewer wants to be WARNED when a known CVE affects a dependency while
// keeping all version upgrades manual/deliberate (no Dependabot, no auto-PRs).
//
// This plugin is applied only at the root. Its `dependencyCheckAggregate` task
// walks every subproject's dependencies, cross-references the NVD database, and
// writes a report under build/reports/dependency-check/. The CI step that runs
// it is advisory (continue-on-error) so a CVE shows up as a warning/artifact
// but NEVER blocks a PR and NEVER changes a version.
//
// Report-only contract:
//   - failBuildOnCVSS = 11f  -> CVSS scores top out at 10, so the build can
//                               never fail on a finding. It only reports.
//
// Running locally / in CI:
//   - First run downloads the full NVD database (slow, can be several minutes).
//   - Public NVD is heavily rate-limited; an NVD_API_KEY dramatically speeds it
//     up and avoids 403/429 throttling. We read it from the NVD_API_KEY env var
//     when present; if absent the scan still runs (just slower / flakier), which
//     is fine because the CI job is advisory and a missing key or NVD outage
//     must never break CI.
dependencyCheck {
    failBuildOnCVSS = 11f // CVSS maxes at 10 => never fail; report-only.
    formats = listOf("HTML", "JSON")
    nvd {
        System.getenv("NVD_API_KEY")?.takeIf { it.isNotBlank() }?.let { apiKey = it }
    }
}

// ---------------------------------------------------------------------------
// Static analysis: detekt (REPORT-ONLY, by Claude)
// ---------------------------------------------------------------------------
// Applied from the root via subprojects{} so no per-module build files change.
// Phase 1 is deliberately non-failing: `ignoreFailures = true` plus a committed
// empty baseline (config/detekt/baseline.xml) mean detekt produces reports and
// never breaks an otherwise-green build. To start gating, flip ignoreFailures
// to false and regenerate the baseline with `./gradlew detektBaseline`.
subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        ignoreFailures = true // phase 1: report-only, do not fail the build
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        baseline = rootProject.file("config/detekt/baseline.xml")
        basePath = rootProject.projectDir.absolutePath
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        reports {
            xml.required.set(true)
            html.required.set(true)
            sarif.required.set(true)
            txt.required.set(false)
            md.required.set(false)
        }
    }

    // NOTE: detekt-formatting (ktlint) is intentionally NOT applied. Its
    // WrappingRule throws a hard analysis exception on some Kotlin 2.x source
    // here, which `ignoreFailures` cannot suppress. The core detekt rulesets
    // run report-only; revisit formatting once on detekt 2.x.
}

// ---------------------------------------------------------------------------
// Coverage: Kover aggregation + verification (by Claude)
// ---------------------------------------------------------------------------
// Kover is applied in each JVM module (core, auth, typed, sessions,
// sessions-email, sessions-sms, sessions-totp, sessions-webauthn, ratelimit).
// Here we aggregate those modules into the root report and add a verification
// rule.
//
// The minimum-coverage bound starts at 0% so `koverVerify` gates the pipeline
// (it runs and would fail on a *drop below* the bound) WITHOUT failing the
// current codebase. RATCHET THIS UP over time as coverage improves.
dependencies {
    kover(project(":core"))
    kover(project(":auth"))
    kover(project(":typed"))
    kover(project(":sessions"))
    kover(project(":sessions-email"))
    kover(project(":sessions-sms"))
    kover(project(":sessions-totp"))
    kover(project(":sessions-webauthn"))
    kover(project(":ratelimit"))
}

kover {
    reports {
        verify {
            rule {
                // TODO(coverage): raise minValue as test coverage grows.
                minBound(0)
            }
        }
    }
}
