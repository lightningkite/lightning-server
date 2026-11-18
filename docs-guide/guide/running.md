# Running

Tests run a server in memory.  To accept real connections, build the definition, hand it to an engine, load
settings, and start it.

## Serving on a port

<!-- sample: com/lightningkite/lightningserver/guide/samples/RunningSamples.kt#running-imports -->
```kotlin
import com.lightningkite.lightningserver.engine.ktor.KtorEngine
import com.lightningkite.lightningserver.settings.loadFromFile
import com.lightningkite.services.kfile.KFile
import io.ktor.server.netty.Netty
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/RunningSamples.kt#serve -->
```kotlin
fun serve() {
    val built = HelloServer.build()
    KtorEngine(built).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        start(Netty)
    }
}
```

`build()` collects the declarations into a `ServerDefinition`.  `loadFromFile` resolves every setting, and
`internalSerializersModule` (a property of the engine) lets it parse your custom setting types.  `start` runs
startup tasks, starts schedules, binds the port (8080 by default), and blocks until the process stops.

The engine needs the `engine-ktor` module.  Netty and JDK engines start the same way; see [Engines](engines.md).

## The first run

The first time, `settings.json` doesn't exist.  `loadFromFile` writes one containing the default of every
setting, then throws `MissingSettingFile`.  Run again and it serves.

Service defaults run in-process (`ram` database and cache, `local` pub/sub, `console` email), so the generated
file starts a working local server.  Edit it to point at real backends; see [Settings](settings.md) for the format and
[Services](services.md) for backend URLs.

If the file exists but lacks a required key (you added a `setting(...)`), `loadFromFile` writes
`settings.suggested.json` beside it, with your values plus defaults for the missing keys, and throws
`IncompleteSettingsException`.  Copy the new keys over and restart.  [Loading the file](settings.md#loading-the-file)
has the details.

## Commands: serve, predeploy, dev

A deployed server has two phases: pre-deploy tasks (migrations, seeding) run once per deploy, and every instance
then serves.  Locally you usually want both in one process.

<!-- sample: com/lightningkite/lightningserver/guide/samples/RunningSamples.kt#predeploy -->
```kotlin
fun predeploy() {
    KtorEngine(HelloServer.build()).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        runPreDeploy()
    }
}

fun dev() {
    KtorEngine(HelloServer.build()).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        settings.ready()
        runPreDeployTasksBlocking()
        start(Netty)
    }
}
```

`runPreDeploy()` runs the pre-deploy tasks and disconnects services.  `runPreDeployTasksBlocking()` leaves them
connected so `start` can follow, but needs `settings.ready()` first.  Pre-deploy and startup tasks are declared in
[Tasks](tasks.md).

Pick the command from `main`:

```kotlin
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "predeploy" -> predeploy()
        "dev" -> dev()
        else -> serve()
    }
}
```

With Gradle's `application` plugin (`mainClass.set("com.example.MainKt")`):

```bash
./gradlew :server:run --args="dev"
```

## Shutdown

`start` registers a JVM shutdown hook.  On `SIGTERM` or Ctrl-C the engine stops accepting connections, cancels
schedules, waits up to `reliability.shutdownDrainTimeout` (25 seconds) for in-flight requests, and disconnects
services.  You write no shutdown code.

## Gotchas

- **Working directory**: `KFile("settings.json")` is relative to the process's working directory.  Gradle's `run`
  task uses the module directory (`server/`), so that is where `settings.json` appears.
- **`start` blocks**: anything after it in `main` runs only after shutdown.
- **The test runner is different**: `testBlocking {}` uses `TestRunner` from `core`, not an engine.  Engine-only
  behavior (body size limits, shutdown, schedule locking) is covered in [Engines](engines.md).

## Next

- [Engines](engines.md): choosing an engine, ports, limits, and tuning
- [Settings](settings.md): the `settings.json` format, per-environment files, secrets
- [Deploying to a VM](deploy-vm.md), [AWS deployment](aws-deployment.md)
