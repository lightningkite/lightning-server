package com.lightningkite.lightningserver.guide.samples

// region tasks-imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.services.cache.Cache
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.time.Duration.Companion.minutes
// endregion tasks-imports

// region task-server
@Serializable
data class WelcomeEmailInput(val address: String, val name: String)

object TaskServer : ServerBuilder() {

    val cache = setting("cache", Cache.Settings())

    // Declare a task on any path. The path acts as a unique identifier — it does not
    // correspond to an HTTP route. A Task executes its body when launched.
    //
    // Use Task { input: TYPE -> ... } (reified) when the input type is known at the call
    // site. The serializer is derived automatically. The handler runs with a ServerRuntime
    // in context, so it has access to settings, services, and other tasks.
    val sendWelcomeEmail = path.path("tasks").path("send-welcome-email") bind Task { input: WelcomeEmailInput ->
        println("Sending welcome email to ${input.address} (name=${input.name})")
        // Record the work in cache so tests can observe the effect.
        cache().set("last-welcome-email", input.address, String.serializer(), 5.minutes)
    }

    // HTTP endpoint that launches the task.
    // launch() submits the task to the engine. In tests the engine executes it
    // synchronously (inline). In production, the engine may queue it via pub/sub
    // for background processing.
    val register = path.path("register").post bind HttpHandler { _ ->
        // In real code you'd parse the request body here and pass it to launch().
        // See the "task-test" region for how to call launch() directly in a test.
        sendWelcomeEmail.launch(WelcomeEmailInput("example@example.com", "Example"))
        HttpResponse.plainText("Registered!")
    }
}
// endregion task-server

// region task-test
fun taskTest() = runBlocking {
    TaskServer.test(settings = {}) {
        // Launch the task directly. In TestRunner, Task.invoke calls executeInline —
        // the task body completes before launch() returns. The effect is immediately
        // visible in the next line.
        TaskServer.sendWelcomeEmail.launch(WelcomeEmailInput("alice@example.com", "Alice"))

        val logged = TaskServer.cache().get("last-welcome-email", String.serializer())
        assertEquals("alice@example.com", logged)
    }
}
// endregion task-test

class TaskSamplesTest {
    @Test
    fun taskRuns() = taskTest()
}
