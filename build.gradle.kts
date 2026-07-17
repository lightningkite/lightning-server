buildscript {
    repositories {
        mavenLocal()
        maven("https://lightningkite-maven.s3.us-west-2.amazonaws.com")
        mavenCentral()
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
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.androidApp) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.graalVmNative) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.vanniktechMavenPublish) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.dependencyCheck)
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
// sessions-email, sessions-sms, ratelimit). Here we aggregate those modules
// into the root report and add a verification rule.
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
