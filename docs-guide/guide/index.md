# Lightning Server

Lightning Server is a Kotlin framework for HTTP and WebSocket backends.  You declare the whole server in plain
Kotlin: endpoints with typed inputs, outputs and auth; the services it needs (database, cache, files, email) as
settings; tasks and schedules.  From that one definition the framework serves requests, generates OpenAPI and typed
client SDKs, runs your tests in memory, and writes the Terraform to deploy it on AWS.

<!-- sample: com/lightningkite/lightningserver/guide/samples/FirstEndpointSamples.kt#echo-server -->
```kotlin
object EchoServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    // POST /echo
    val echo = path.path("echo").post bind ApiHttpHandler(
        summary = "Echo a message",
        description = "Returns the message back with its character count.",
        auth = noAuth,
        implementation = { input: EchoRequest ->
            EchoResponse(
                echo = input.message,
                length = input.message.length
            )
        }
    )
}
```

Tested without a port or a network:

<!-- sample: com/lightningkite/lightningserver/guide/samples/FirstEndpointSamples.kt#echo-server-test -->
```kotlin
fun echoServerTest() = EchoServer.testBlocking(settings = {}) {
    val result = EchoServer.echo.test(null, EchoRequest("ping"))
    check(result.echo == "ping")
    check(result.length == 4)
}
```

Kotlin blocks in these docs are compiled and checked against the sample sources on every build.

## Learning path

1. **[Overview](overview.md)**: the mental model (definition, settings, engine) and the vocabulary.
2. **[Setup](setup.md)**: Gradle configuration.
3. **[Your first endpoint](first-endpoint.md)**, then **[Routing](routing.md)** and
   **[Typed endpoints](typed-endpoints.md)**.
4. **[Services](services.md)** and **[Database](database.md)**: store data; swap backends by URL.
5. **[Model REST endpoints](model-rest.md)** and **[Permissions](permissions.md)**: a full CRUD API per model.
6. **[Authentication](auth.md)** and **[Proof-based login](proof-session.md)**: who the caller is, and how they log in.
7. **[Testing](testing.md)**, **[Running](running.md)**, then
   **[Deploying to AWS](aws-deployment.md)**.

Short on time?  The **[Condensed guide](condensed-guide.md)** shows every feature as one short, tested example.

## Find it by task

| I want to... | Read |
|---|---|
| Accept and return JSON, with docs and an SDK | [Typed endpoints](typed-endpoints.md), [Client SDKs](client-sdks.md) |
| Reject bad input with a clear error | [Validation](validation.md), [Error handling](error-handling.md) |
| Push live updates to clients | [WebSockets](websockets.md), [Realtime model updates](model-realtime.md), [Pub/Sub](pubsub.md) |
| Store and serve uploaded files or images | [Files](files.md), [Media](media.md) |
| Send email, SMS, or push notifications | [Email and SMS](email.md), [Notifications](notifications.md) |
| Log users in with a PIN, password, passkey, or Google | [Proof-based login](proof-session.md), [OAuth](oauth.md) |
| Let MCP clients such as Claude act as a user | [OAuth authorization server](oauth-provider.md) |
| Run work later or on a timer | [Tasks](tasks.md), [Schedules](schedules.md) |
| Protect the server from abuse | [Rate limiting](rate-limiting.md), [CORS and interceptors](cors-interceptors.md) |
| See what production is doing | [Observability](observability.md), [Meta endpoints and admin](meta-admin.md) |
| Deploy | [AWS Lambda](aws-deployment.md), [EC2](deploy-ec2.md), [a VM or container](deploy-vm.md), [Terraform](terraform.md) |
| Understand how it works underneath | [Architecture](architecture.md) |

Coming from another framework: [Django](appendix/vs-django.md), [Rails](appendix/vs-rails.md),
[Spring](appendix/vs-spring.md).  Upgrading: [to epoch 1](appendix/migration-to-epoch-1.md),
[v5.1 to v5.2](appendix/migration-v5.1-to-v5.2.md), [v4 to v5](appendix/migration-v4-to-v5.md).

## Source and license

- [GitHub repository](https://github.com/lightningkite/lightning-server)
- [Changelog](https://github.com/lightningkite/lightning-server/blob/master/CHANGELOG.md)
- [License (Apache 2.0)](https://github.com/lightningkite/lightning-server/blob/master/LICENSE.txt)
