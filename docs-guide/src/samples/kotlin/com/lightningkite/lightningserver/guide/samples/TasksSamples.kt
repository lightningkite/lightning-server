package com.lightningkite.lightningserver.guide.samples

// region tasks-imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.cache.*
import com.lightningkite.services.database.*
import kotlinx.serialization.Serializable
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes
// endregion tasks-imports

// region tasks-server
@Serializable
data class WelcomeEmail(val address: String, val name: String)

object TasksServer : ServerBuilder() {
    val cache = setting("cache", Cache.Settings())

    val sendWelcomeEmail = path.path("tasks").path("send-welcome-email") bind Task { input: WelcomeEmail ->
        // Stand-in for sending the email.
        cache().set("welcomed:${input.address}", input.name)
    }

    val register = path.path("register").arg<String>("email").post bind HttpHandler { request ->
        sendWelcomeEmail.launch(WelcomeEmail(address = request.arg1, name = "New user"))
        HttpResponse.plainText("Registered")
    }
}
// endregion tasks-server

// region tasks-test
fun tasksLaunchTest() = TasksServer.testBlocking(settings = {}) {
    TasksServer.register.test("alice@example.com")
    assertEquals("New user", TasksServer.cache().get<String>("welcomed:alice@example.com"))

    // launch() needs a running execution; execute { } gives the test one.
    execute { TasksServer.sendWelcomeEmail.launch(WelcomeEmail("bob@example.com", "Bob")) }
    assertEquals("Bob", TasksServer.cache().get<String>("welcomed:bob@example.com"))
}
// endregion tasks-test

// region tasks-timeout
val monthlyReport = Task(timeout = 30.minutes) { month: String ->
    println("Building the report for $month")
}
// endregion tasks-timeout

// region tasks-lifecycle
@Serializable
data class VisitCounter(override val _id: String, val value: Int) : HasId<String>

object TasksLifecycleServer : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val counters = database.registerTable<VisitCounter>("VisitCounter")

    val seedCounters = path.path("predeploy").path("seed-counters") bind PreDeployTask(
        dependencies = { listOf(counters.preDeployTask) },
    ) {
        if (counters().get("visits") == null) counters().insertOne(VisitCounter("visits", 0))
    }

    val logBoot = path.path("startup").path("log-boot") bind StartupTask {
        println("Instance starting")
    }
}
// endregion tasks-lifecycle

// region tasks-lifecycle-test
fun tasksLifecycleTest() = TasksLifecycleServer.testBlocking(settings = {}) {
    executePreDeployTasks()
    executePreDeployTasks()  // every deploy runs them again, so they must be safe to repeat
    assertEquals(0, TasksLifecycleServer.counters().get("visits")?.value)

    executeStartupTasks()
}
// endregion tasks-lifecycle-test
