Lightning Server — Project-Specific Development Guidelines

Audience: Advanced Kotlin developers contributing to this monorepo.
Last updated: 2025-09-05

Overview
- This is a multi-module Kotlin monorepo (Kotlin 2.2.x). Key modules: core, typed, sessions, engine-local, ktor, aws-serverless, vertx, etc. Dependencies/versions are centrally managed in gradle/libs.versions.toml.
- The project provides a rich test harness (runtime.test.*), a pathing/serialization layer, and abstractions for runtime engines (Ktor/AWS/etc.).

Build and Configuration
- Tooling/Repos
  - Gradle build uses repositories: lightningkite S3 maven, Maven Central, Google (see build.gradle.kts and settings.gradle.kts for plugin repos).
  - Kotlin plugins are defined via Gradle version catalogs (see gradle/libs.versions.toml). Kotlin=2.2.10, kotlinx-serialization=1.9.0, ktor=3.2.x.
- Module inclusion
  - settings.gradle.kts includes many modules; some OAuth modules are disabled by default.
  - If a module causes build pain locally, you can temporarily exclude it by commenting it out in settings.gradle.kts (keep this change local; do not commit unless coordinated).
- Partial builds
  - Build a specific module:
    - ./gradlew :core:build
    - ./gradlew :typed:build
  - Run a subset of tasks without touching others (faster, avoids unrelated errors):
    - ./gradlew :core:compileKotlin :core:testClasses

Known Build Caveats
- Kotlin FIR analyzer sometimes chokes on multiplatform/JS bits during full monorepo builds (e.g., sessions-shared:commonMain) on some machines/SDK mixes. If you hit FIR exceptions (NullPointerException in FirRegularClassSymbol), prefer module-scoped builds and tests (see “Testing” below). Upgrading Kotlin toolchain or cleaning Gradle caches often resolves it.
  - Remedies:
    - ./gradlew --stop && ./gradlew clean
    - Bump Gradle JVM memory (already configured in gradle.properties) if using IDE runner.
    - Run only the needed module (./gradlew :core:test); avoid building JS targets if unrelated.

Testing
- Test frameworks
  - kotlin.test is used; JUnit engine is present for JVM tests. Tests typically live under <module>/src/test/kotlin.
- Running tests
  - Per module (recommended):
    - ./gradlew :core:test
    - ./gradlew :typed:test
  - Single test class:
    - ./gradlew :core:test --tests "com.lightningkite.lightningserver.WebSocketConnectRequestTest"
  - Single JVM method:
    - ./gradlew :core:test --tests "com.lightningkite.lightningserver.WebSocketConnectRequestTest.serialization"
- In-Project Test Utilities
  - runtime.test helpers build typed requests to endpoints and websocket handlers without bringing up a real engine. Examples:
    - HTTP: endpoint.test() returns HttpResponse inline to the test.
    - Websockets: handler.test() returns a TestRunner implementing WebSocketConnection; you can send frames and observe messages via onMessageSent.
  - ServerBuilder.test settings = { } establishes a test runtime with default settings and the UnitTest-like engine.

Adding New Tests
- Pattern: initialize server definitions and then call .test() on endpoints/handlers. Ensure Settings are populated as needed for your test.
- Example: HTTP endpoint test (refer to docs/setup.md test pattern):
  - Settings.populateDefaults(mapOf()) to enforce explicit defaults.
  - engine-local is not required when using the test harness.
- Example: Websocket multiplex test pattern (see core/src/test/.../MultiplexWebSocketHandlerTest.kt):
  - Use ServerBuilder with topics and handlers bound via definition.builder DSL.
  - Use TestRunner to simulate frames; use server.externalSerialization.json to encode/decode MultiplexMessage.

Serialization, Paths, and Caching (for debugging failures)
- serializerOrContextual: For round-trips in tests without a module’s serializers module, use com.lightningkite.lightningserver.serialization.serializerOrContextual(). It falls back to ContextualSerializer when no contextual serializer exists; be aware that pretty-printing/encodeDefaults may change byte-for-byte outputs but equals should round-trip for data classes that implement correct equals.
- RawPath<PATH>: Serializable via PathSerializer. Equality is string-based; hash +1 to reduce collisions with other strings in maps. When deserializing WebSocketConnectRequest in tests, ensure you pass the generic parameter (e.g., PathSpec0) consistently.
- SerializableCache: Serializable via SerializableCacheSerializer, equality compares encoded byte arrays by content. In tests, using cache.get(calculatingKey, input) will compute and store results; ensure you have a ServerRuntime context.

Minimal Verified Test Example
- The following is the minimal structure for a passing test inside a module such as core:
  - File: core/src/test/kotlin/SanityTest.kt
    - package com.lightningkite.lightningserver
    - import kotlin.test.Test
    - import kotlin.test.assertTrue
    - class SanityTest { @Test fun passes() { assertTrue(true) } }
- Run:
  - ./gradlew :core:test --tests "*SanityTest*"
- Note: In CI or on developer machines where a full root build triggers unrelated module failures, invoking only :core:test isolates JVM tests for core and avoids FIR/JS issues from other modules.

Troubleshooting Test Failures in This Repo
- If run_test or IDE runner shows tests failing without output:
  - Prefer Gradle CLI for better error logs (./gradlew :core:test --info).
  - Ensure you’re not accidentally building the whole repo; constrain the task to the module under test.
  - If websocket tests fail on JSON encode/decode, ensure you’re using the runtime’s externalSerialization.json from the active ServerRuntime and not a default Json instance; the transport format/leniency may differ.
- If equality assertions fail after round-trip:
  - Verify that @Serializable annotations and custom serializers are applied (RawPath has a custom serializer).
  - Check defaults: encodeDefaults may persist default values; adjust Json configuration if comparing strings.

Code Style/Practices
- Official Kotlin code style is enabled (gradle.properties: kotlin.code.style=official).
- Favor the DSLs provided by definition.builder for constructing server routes and by runtime.test for tests.
- When adding websocket tests:
  - Use server.externalSerialization.json tied to the TestRunner context to avoid mismatches.
  - Observe frames via mux.onMessageSent; keep last frame and decode via MultiplexMessage.serializer().

Release/Publishing
- Maven publishing uses Vanniktech; versions are catalog-driven. Coordinate with maintainers before changing libs.versions.toml or plugin versions.

Do/Don’t
- Do: Scope Gradle commands to a module when iterating on tests.
- Do: Use the provided test harness instead of spinning up engines.
- Don’t: Commit temporary tests or disable modules without prior discussion.
- Don’t: Introduce non-deterministic tests—this repo emphasizes deterministic runtime/test harnesses.
