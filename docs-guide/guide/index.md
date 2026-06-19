# Lightning Server

Lightning Server is a Kotlin server framework for building production backends
faster.  You define typed endpoints, service settings, and authentication rules
in plain Kotlin; the framework handles HTTP routing, SDK generation, unit
testing (no running server required), and derives your complete AWS Terraform
infrastructure from the same server definition.

**Status:** pre-release (version 5).  The API is settling and breaking changes
are still possible.

---

## Why Lightning Server?

- **Typed endpoints end-to-end** — input, output, auth user, and error cases
  are all Kotlin types; the framework auto-generates an OpenAPI spec and
  type-safe client SDKs from them.
- **Swappable service abstractions** — Database, Cache, Files, Email, SMS, and
  PubSub are declared as settings.  Swap implementations (MongoDB &harr; in-process
  JSON, Redis &harr; RAM map) by changing one URL string; no application code
  changes.
- **Infrastructure from your server definition** — a
  `TerraformAwsServerlessBuilder` subclass reads your service settings and
  emits `.tf.json` files for Lambda, API Gateway, S3, DynamoDB, MongoDB Atlas,
  CloudWatch alarms, and more.
- **First-class unit testing** — `SERVER.test {}` starts an ephemeral
  in-process runtime; `endpoint.test(auth, input)` returns typed output
  directly.  No Docker, no ports, no external services needed.

---

## Guide Chapters

| Chapter | What it covers |
|---|---|
| [Your First Endpoint](first-endpoint.md) | `ServerBuilder`, `HttpHandler`, `HttpResponse`, running your first test |
| [Routing & Path Parameters](routing.md) | Nested paths, all HTTP methods, typed path args, sub-builders, query params |
| [Typed Endpoints, Errors & SDK Generation](typed-endpoints.md) | `ApiHttpHandler`, `LSError`, `errorCases`, success codes, SDK generation |
| [Services & Settings](services.md) | Declaring and swapping service settings; in-process RAM mocks for tests |
| [Database & the Query DSL](database.md) | Type-safe `condition {}` / `modification {}` DSL, `@GenerateDataClassPaths`, CRUD |
| [Authentication & Sessions](auth.md) | `PrincipalType`, `User.require()`, reading `auth.id` / `auth.fetch()` in handlers |
| [Testing Your Server](testing.md) | `SERVER.test {}`, `HttpHandler.test()`, `ApiHttpHandler.test(auth, input)`, `@Test` |
| [Deploying to AWS](aws-deployment.md) | `AwsAdapter`, `TerraformAwsServerlessBuilder`, service &rarr; Terraform, deploy workflow |

---

## Quick Start

```kotlin
object HelloServer : ServerBuilder() {

    // GET / — responds with a plain-text greeting
    val root = path.get bind HttpHandler {
        HttpResponse.plainText("Hello, Lightning Server!")
    }
}
```

Test it without starting a server:

```kotlin
fun helloServerTest() = runBlocking {
    HelloServer.test(settings = {}) {
        val response = HelloServer.root.test()
        check(response.body?.text() == "Hello, Lightning Server!")
    }
}
```

Start with **[Your First Endpoint](first-endpoint.md)** for the full walkthrough.

---

## Source & License

- [GitHub Repository](https://github.com/lightningkite/lightning-server)
- [LICENSE.txt](https://github.com/lightningkite/lightning-server/blob/master/LICENSE.txt)
