# Lightning Server: Recommendations for Improvement

An external assessment of what could make Lightning Server more accessible to developers outside LightningKite.

## Executive Summary

Lightning Server has a clean core HTTP abstraction and surprisingly good endpoint documentation. The main barriers to
adoption are:

1. Undocumented design philosophy
2. Incomplete documentation in key areas
3. Implicit coupling to the `serviceAbstractions` ecosystem
4. Assumptions that make sense internally but aren't explained externally

---

## Documentation Improvements

### 1. Add a "Philosophy & Design" Document

**Problem**: There's no explanation of *why* Lightning Server exists or what tradeoffs it makes.

**Recommendation**: Create `docs/philosophy.md` covering:

- What problem does Lightning Server solve?
- Who is the target audience?
- What are the core design principles?
- How does it compare to alternatives (Ktor, Spring, http4k)?
- What are the explicit tradeoffs?

The closest thing currently is one line in setup.md:
> "It is considered an important Lightning Server principal to ensure your application works out of the box with the
> generated settings.json."

That's a good principle - but it needs expansion. Other principles to document:

- Settings-driven configuration
- Service abstraction over implementation
- Type-safe endpoints with auto-generated SDKs
- "Batteries included" approach

### 2. Complete the Stub Documentation

**Problem**: Some docs are essentially empty.

| File            | Current Size | Issue                      |
|-----------------|--------------|----------------------------|
| `websockets.md` | 35 bytes     | Just a heading, no content |
| `deploy-vm.md`  | 20 bytes     | Just "TODO"                |

**Recommendation**: Either complete these docs or remove them from the docs folder. Empty docs are worse than no docs -
they suggest the feature exists but leave users stranded.

For WebSockets specifically, the demo uses `MultiplexWebSocketHandler` - that functionality exists and should be
documented.

### 3. Document the Service Abstractions Relationship

**Problem**: Lightning Server depends heavily on `com.lightningkite.services:*` modules, but this relationship is
invisible in documentation.

Looking at `demo/Server.kt`:

```kotlin
import com.lightningkite.services.database.*
import com.lightningkite.services.database.jsonfile.JsonFileDatabase
import com.lightningkite.services.database.mongodb.*
import com.lightningkite.services.cache.*
import com.lightningkite.services.email.*
import com.lightningkite.services.files.*
import com.lightningkite.services.sms.*
```

**Recommendation**: Create `docs/service-abstractions.md` explaining:

- What is the `serviceAbstractions` project?
- How does it relate to Lightning Server?
- What modules are available?
- How do you add a new service implementation?
- Where is the source code / documentation for that project?

### 4. Add Troubleshooting / FAQ Section

**Problem**: When things go wrong, users have no guidance.

**Recommendation**: Create `docs/troubleshooting.md` with:

- Common errors and their solutions
- Debugging tips (how to enable verbose logging, etc.)
- How to report issues
- Known limitations

Example entries:

- "Why do I get `DuplicateRegistrationError` in tests?" (Answer: Server is being built multiple times)
- "Why does my endpoint return 500 with no error message?" (Answer: Check serialization of response types)
- "Settings file not being read" (Answer: Check file path, run twice on first setup)

### 5. Add Migration / Upgrade Guide

**Problem**: Version 5 is in development (`version-5-SNAPSHOT`). No documentation on what changed or how to migrate.

**Recommendation**: Create `docs/migration.md` with:

- Changelog of breaking changes between versions
- Step-by-step migration instructions
- Deprecation warnings and their replacements

---

## Reducing Specialization

These recommendations are about making the framework more accessible to users who don't share all of LightningKite's
assumptions.

### 6. Provide Minimal Examples

**Problem**: The demo is comprehensive but overwhelming. It includes:

- Multi-factor auth with 5 different proof types
- LLM chat assistants
- External channel support (SMS, email)
- Blog endpoints
- File uploads
- WebSockets
- Terraform generation

A new user can't easily see "what's the minimum I need?"

**Recommendation**: Add a `examples/` directory with graduated complexity:

```
examples/
  minimal-http/          # Just HTTP endpoints, no services
  with-database/         # Add database
  with-auth/             # Add authentication
  full-featured/         # Current demo
```

The `minimal-http` example might be:

```kotlin
object Server : ServerBuilder() {
    val hello = path.path("hello").get bind HttpHandler {
        HttpResponse.plainText("Hello, World!")
    }

    val echo = path.path("echo").post bind HttpHandler { request ->
        HttpResponse.plainText(request.body?.text() ?: "")
    }
}

fun main() {
    val built = Server.build()
    KtorEngine(built).start(Netty)
}
```

No settings file, no database, no services - just HTTP.

### 7. Document Escape Hatches

**Problem**: The framework is opinionated, but users may have different needs. How do they work around the opinions?

**Recommendation**: Add a section to relevant docs (or a dedicated `docs/customization.md`) covering:

- How to use a custom auth system instead of `AuthEndpoints`
- How to use raw SQL instead of the query DSL
- How to add a custom service implementation
- How to bypass interceptors for specific endpoints
- How to use Lightning Server's HTTP layer without its service abstractions

### 8. Document Engine Selection

**Problem**: Setup guide assumes Ktor. Three other engines exist but aren't explained.

**Recommendation**: Add `docs/engines.md` or expand `docs/runtime.md` to cover:

| Engine                  | Best For                 | Tradeoffs            |
|-------------------------|--------------------------|----------------------|
| `engine-ktor`           | Development, familiarity | Adds Ktor dependency |
| `engine-netty`          | Performance              | Lower-level          |
| `engine-jdk-server`     | Minimal dependencies     | JDK 18+              |
| `engine-aws-serverless` | Lambda deployment        | AWS-specific         |

Include benchmarks if available.

### 9. Clarify the PostgreSQL Status

**Problem**: The database docs say:
> "WARNING - Support is not considered ready for production. If you wish to use this, reach out to us and we'll polish
> it off."

This is honest, but it leaves users uncertain about what works and what doesn't.

**Recommendation**: Be more specific:

- What exactly doesn't work? (Currently says "Map modifications do not")
- What percentage of the test suite passes?
- Is there a tracking issue for full PostgreSQL support?
- What's the timeline/priority?

---

## Structural Recommendations

### 10. Consider Separating Core HTTP from Batteries

**Problem**: To use Lightning Server's HTTP handling, you currently need to understand the settings system, service
abstractions, and engine architecture.

**Question to consider**: Could `core` be usable standalone, without `serviceAbstractions`?

This would allow:

- Users who just want the HTTP abstraction to adopt it without the ecosystem
- Gradual adoption path (start with HTTP, add services as needed)
- Clearer separation of concerns

This is a larger architectural decision, not a documentation fix.

### 11. Improve Discoverability

**Problem**: Good documentation exists but may be hard to find.

**Recommendations**:

- Add a `docs/README.md` or `docs/index.md` with a table of contents
- Add "See Also" sections at the bottom of each doc (some have this, make it consistent)
- Consider a documentation site (GitBook, Docusaurus, MkDocs)

---

## What's Already Good

To be clear, many things are done well:

- **`endpoints.md`** (21 KB) - Comprehensive coverage of routing, headers, cookies, body parsing, interceptors, best
  practices
- **`authentication.md`** (8 KB) - Solid coverage of the auth system
- **`database.md`** (7.7 KB) - Clear examples of conditions, modifications, signals
- **`deploy-aws.md`** (8.7 KB) - Good AWS deployment guide
- **Core API surface** - `HttpHandler` has 1 method. `HttpRequest` is a simple data class. This is much cleaner than
  many frameworks.
- **Type safety** - Path arguments, query parameters, and bodies are type-safe
- **Testing support** - `LocalEngine` and `.test()` extension make testing straightforward

---

## Priority Order

If addressing these incrementally:

1. **Complete stub docs** (`websockets.md`, `deploy-vm.md`) - Low effort, high impact on perceived completeness
2. **Add philosophy doc** - Helps users self-select whether this framework fits their needs
3. **Document service abstractions** - Core to understanding the ecosystem
4. **Add minimal examples** - Reduces barrier to entry
5. **Add troubleshooting** - Reduces support burden
6. **Document escape hatches** - Increases confidence for adoption
7. **Migration guide** - Important before v5 release

---

## Conclusion

Lightning Server is more polished than it initially appears. The core abstractions are clean, and the endpoint
documentation is genuinely good. The main improvements needed are:

1. Explaining the "why" (philosophy)
2. Filling documentation gaps (WebSockets, VM deployment)
3. Making the ecosystem relationship explicit (service abstractions)
4. Providing on-ramps for users with different needs (minimal examples, escape hatches)

These are documentation and positioning challenges, not fundamental architectural problems.
