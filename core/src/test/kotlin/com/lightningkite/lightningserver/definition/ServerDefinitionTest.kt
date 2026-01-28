// Tests created by Claude
package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.definition.builder.DuplicateRegistrationError
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpInterceptor
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.http.put
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Tests for ServerDefinition class, focusing on the module flattening,
 * endpoint registration, reverse lookups, and interceptor compilation.
 */
class ServerDefinitionTest {

    // ==================== Basic ServerBuilder Tests ====================

    @Test
    fun `building empty server produces valid definition`() {
        val emptyServer = object : ServerBuilder() {}
        val definition = emptyServer.build()

        assertNotNull(definition)
        assertTrue(definition.endpoints.entries.isEmpty())
        assertTrue(definition.tasks.isEmpty())
        assertTrue(definition.schedules.isEmpty())
        assertTrue(definition.startupTasks.isEmpty())
    }

    @Test
    fun `basic endpoint registration works`() {
        val server = object : ServerBuilder() {
            val root = path.get bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
        }

        val definition = server.build()
        val rootEndpoints = definition.endpoints[PathSpec.root]

        assertNotNull(rootEndpoints, "Should have endpoints at root")
        assertNotNull(rootEndpoints.http[HttpMethod.GET], "Should have GET handler at root")
    }

    @Test
    fun `multiple endpoints at different paths`() {
        val server = object : ServerBuilder() {
            val root = path.get bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
            val users = path.path("users").get bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
            val posts = path.path("posts").get bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
        }

        val definition = server.build()

        assertNotNull(definition.endpoints[PathSpec.root]?.http?.get(HttpMethod.GET))
        assertNotNull(definition.endpoints[PathSpec.root.path("users")]?.http?.get(HttpMethod.GET))
        assertNotNull(definition.endpoints[PathSpec.root.path("posts")]?.http?.get(HttpMethod.GET))
    }

    @Test
    fun `multiple HTTP methods at same path`() {
        val server = object : ServerBuilder() {
            val getRoot = path.get bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
            val postRoot = path.post bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
            val putRoot = path.put bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
        }

        val definition = server.build()
        val rootEndpoints = definition.endpoints[PathSpec.root]

        assertNotNull(rootEndpoints?.http?.get(HttpMethod.GET))
        assertNotNull(rootEndpoints?.http?.get(HttpMethod.POST))
        assertNotNull(rootEndpoints?.http?.get(HttpMethod.PUT))
        assertEquals(3, rootEndpoints?.http?.size)
    }

    // ==================== Module Inclusion Tests ====================

    @Test
    fun `module inclusion at root`() {
        val subModule = object : ServerBuilder() {
            val subEndpoint = path.path("sub").get bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
        }

        val mainServer = object : ServerBuilder() {
            val main = path.get bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
            val included = path include subModule
        }

        val definition = mainServer.build()

        assertNotNull(definition.endpoints[PathSpec.root]?.http?.get(HttpMethod.GET))
        assertNotNull(definition.endpoints[PathSpec.root.path("sub")]?.http?.get(HttpMethod.GET))
    }

    @Test
    fun `module inclusion at prefix path`() {
        val apiModule = object : ServerBuilder() {
            val users = path.path("users").get bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
            val posts = path.path("posts").get bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
        }

        val mainServer = object : ServerBuilder() {
            val root = path.get bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
            val api = path.path("api").path("v1") include apiModule
        }

        val definition = mainServer.build()

        assertNotNull(definition.endpoints[PathSpec.root]?.http?.get(HttpMethod.GET))
        assertNotNull(definition.endpoints[PathSpec.root.path("api").path("v1").path("users")]?.http?.get(HttpMethod.GET))
        assertNotNull(definition.endpoints[PathSpec.root.path("api").path("v1").path("posts")]?.http?.get(HttpMethod.GET))
    }

    @Test
    fun `nested module inclusion`() {
        val innerModule = object : ServerBuilder() {
            val endpoint = path.path("inner").get bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
        }

        val middleModule = object : ServerBuilder() {
            val middle = path.path("middle").get bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
            val inner = path include innerModule
        }

        val outerServer = object : ServerBuilder() {
            val outer = path.path("outer").get bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
            val middle = path.path("nested") include middleModule
        }

        val definition = outerServer.build()

        assertNotNull(definition.endpoints[PathSpec.root.path("outer")]?.http?.get(HttpMethod.GET))
        assertNotNull(definition.endpoints[PathSpec.root.path("nested").path("middle")]?.http?.get(HttpMethod.GET))
        assertNotNull(definition.endpoints[PathSpec.root.path("nested").path("inner")]?.http?.get(HttpMethod.GET))
    }

    // ==================== Duplicate Registration Error Tests ====================

    @Test
    fun `duplicate endpoint registration in same module throws`() {
        assertFailsWith<DuplicateRegistrationError> {
            object : ServerBuilder() {
                val first = path.path("test").get bind HttpHandler<PathSpec0> {
                    HttpResponse(status = HttpStatus.OK)
                }
                // Attempting to register same path and method again
                val second = path.path("test").get bind HttpHandler<PathSpec0> {
                    HttpResponse(status = HttpStatus.OK)
                }
            }.build()
        }
    }

    @Test
    fun `duplicate endpoint across modules throws on flatten`() {
        val moduleA = object : ServerBuilder() {
            val endpoint = path.path("shared").get bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
        }

        val moduleB = object : ServerBuilder() {
            val endpoint = path.path("shared").get bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
        }

        assertFailsWith<DuplicateRegistrationError> {
            val server = object : ServerBuilder() {
                val a = path include moduleA
                val b = path include moduleB
            }
            val definition = server.build()
            // Flatten is lazy - need to access endpoints to trigger it
            definition.endpoints
        }
    }

    @Test
    fun `different methods at same path do not conflict`() {
        val moduleA = object : ServerBuilder() {
            val getEndpoint = path.path("shared").get bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
        }

        val moduleB = object : ServerBuilder() {
            val postEndpoint = path.path("shared").post bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
        }

        // Should not throw - different methods are OK
        val server = object : ServerBuilder() {
            val a = path include moduleA
            val b = path include moduleB
        }

        val definition = server.build()
        val sharedEndpoints = definition.endpoints[PathSpec.root.path("shared")]

        assertNotNull(sharedEndpoints?.http?.get(HttpMethod.GET))
        assertNotNull(sharedEndpoints?.http?.get(HttpMethod.POST))
    }

    // ==================== Interceptor Compilation Tests ====================

    @Test
    fun `http interceptors are collected from server`() {
        val interceptor = HttpInterceptor { request, cont ->
            cont(request)
        }

        val server = object : ServerBuilder() {
            init {
                install(interceptor)
            }
            val root = path.get bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
        }

        val definition = server.build()

        assertEquals(1, definition.httpInterceptors.size)
        assertTrue(definition.httpInterceptors.contains(interceptor))
    }

    @Test
    fun `multiple interceptors preserve order`() {
        val interceptor1 = HttpInterceptor { request, cont -> cont(request) }
        val interceptor2 = HttpInterceptor { request, cont -> cont(request) }
        val interceptor3 = HttpInterceptor { request, cont -> cont(request) }

        val server = object : ServerBuilder() {
            init {
                install(interceptor1)
                install(interceptor2)
                install(interceptor3)
            }
            val root = path.get bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
        }

        val definition = server.build()

        assertEquals(3, definition.httpInterceptors.size)
        // Interceptors should be in same order as installation
        assertEquals(interceptor1, definition.httpInterceptors[0])
        assertEquals(interceptor2, definition.httpInterceptors[1])
        assertEquals(interceptor3, definition.httpInterceptors[2])
    }

    @Test
    fun `interceptors from modules are combined`() {
        val parentInterceptor = HttpInterceptor { request, cont -> cont(request) }
        val childInterceptor = HttpInterceptor { request, cont -> cont(request) }

        val childModule = object : ServerBuilder() {
            init {
                install(childInterceptor)
            }
            val endpoint = path.path("child").get bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
        }

        val parentServer = object : ServerBuilder() {
            init {
                install(parentInterceptor)
            }
            val child = path include childModule
        }

        val definition = parentServer.build()

        // Both interceptors should be present
        assertEquals(2, definition.httpInterceptors.size)
        assertTrue(definition.httpInterceptors.contains(parentInterceptor))
        assertTrue(definition.httpInterceptors.contains(childInterceptor))
    }

    // ==================== Task Registration Tests ====================

    @Test
    fun `task registration works`() {
        val server = object : ServerBuilder() {
            val myTask = path.path("tasks").path("my-task") bind Task<String> {
                // Task implementation
            }
        }

        val definition = server.build()

        assertEquals(1, definition.tasks.size)
        assertNotNull(definition.tasks[PathSpec.root.path("tasks").path("my-task")])
    }

    @Test
    fun `scheduled task registration works`() {
        val server = object : ServerBuilder() {
            val cleanupSchedule = path.path("schedules").path("cleanup") bind ScheduledTask(10.minutes) {
                // Scheduled task implementation
            }
        }

        val definition = server.build()

        assertEquals(1, definition.schedules.size)
        assertNotNull(definition.schedules[PathSpec.root.path("schedules").path("cleanup")])
    }

    @Test
    fun `startup task registration works`() {
        val server = object : ServerBuilder() {
            val initTask = path.path("init") bind StartupTask {
                // Startup task implementation
            }
        }

        val definition = server.build()

        assertEquals(1, definition.startupTasks.size)
        assertNotNull(definition.startupTasks[PathSpec.root.path("init")])
    }

    @Test
    fun `tasks from modules are flattened with correct paths`() {
        val moduleA = object : ServerBuilder() {
            val task = path.path("task-a") bind Task<String> {}
        }

        val moduleB = object : ServerBuilder() {
            val task = path.path("task-b") bind Task<Int> {}
        }

        val server = object : ServerBuilder() {
            val a = path.path("module-a") include moduleA
            val b = path.path("module-b") include moduleB
        }

        val definition = server.build()

        assertEquals(2, definition.tasks.size)
        assertNotNull(definition.tasks[PathSpec.root.path("module-a").path("task-a")])
        assertNotNull(definition.tasks[PathSpec.root.path("module-b").path("task-b")])
    }

    // ==================== Reverse Lookup Tests ====================

    @Test
    fun `location lookup for http handler`() {
        val handler = HttpHandler<PathSpec0> {
            HttpResponse(status = HttpStatus.OK)
        }

        val server = object : ServerBuilder() {
            val root = path.path("api").path("hello").get bind handler
        }

        val definition = server.build()
        val location = definition.location(handler)

        assertNotNull(location, "Should find location for handler")
        assertEquals(PathSpec.root.path("api").path("hello"), location.path)
        assertEquals(HttpMethod.GET, location.method)
    }

    @Test
    fun `location lookup for unknown handler returns null`() {
        val registeredHandler = HttpHandler<PathSpec0> {
            HttpResponse(status = HttpStatus.OK)
        }

        val unregisteredHandler = HttpHandler<PathSpec0> {
            HttpResponse(status = HttpStatus.OK)
        }

        val server = object : ServerBuilder() {
            val endpoint = path.get bind registeredHandler
        }

        val definition = server.build()

        assertNotNull(definition.location(registeredHandler))
        assertNull(definition.location(unregisteredHandler))
    }

    @Test
    fun `location lookup for task`() {
        val task = Task<String> {}

        val server = object : ServerBuilder() {
            val myTask = path.path("tasks").path("process") bind task
        }

        val definition = server.build()
        val location = definition.location(task)

        assertNotNull(location)
        assertEquals(PathSpec.root.path("tasks").path("process"), location)
    }

    @Test
    fun `location lookup for scheduled task`() {
        val scheduledTask = ScheduledTask(10.minutes) {}

        val server = object : ServerBuilder() {
            val schedule = path.path("schedules").path("cleanup") bind scheduledTask
        }

        val definition = server.build()
        val location = definition.location(scheduledTask)

        assertNotNull(location)
        assertEquals(PathSpec.root.path("schedules").path("cleanup"), location)
    }

    @Test
    fun `location lookup for startup task`() {
        val startupTask = StartupTask {}

        val server = object : ServerBuilder() {
            val init = path.path("startup").path("init") bind startupTask
        }

        val definition = server.build()
        val location = definition.location(startupTask)

        assertNotNull(location)
        assertEquals(PathSpec.root.path("startup").path("init"), location)
    }

    // ==================== Settings Tests ====================

    @Test
    fun `settings are collected from server`() {
        val server = object : ServerBuilder() {
            val mySetting = setting("my-setting", "default", String.serializer())
        }

        val definition = server.build()

        assertTrue(definition.settings.any { it.name == "my-setting" })
    }

    @Test
    fun `settings from modules are merged without duplicates`() {
        val moduleA = object : ServerBuilder() {
            val settingA = setting("setting-a", 42, Int.serializer())
        }

        val moduleB = object : ServerBuilder() {
            val settingB = setting("setting-b", "hello", String.serializer())
        }

        val server = object : ServerBuilder() {
            val mainSetting = setting("main-setting", true, Boolean.serializer())
            val a = path include moduleA
            val b = path include moduleB
        }

        val definition = server.build()
        val settingNames = definition.settings.map { it.name }.toSet()

        assertTrue(settingNames.contains("main-setting"))
        assertTrue(settingNames.contains("setting-a"))
        assertTrue(settingNames.contains("setting-b"))
    }

    @Test
    fun `duplicate setting names are deduplicated by distinctBy`() {
        // Both modules define a setting with the same name
        val moduleA = object : ServerBuilder() {
            val shared = setting("shared-name", "valueA", String.serializer())
        }

        val moduleB = object : ServerBuilder() {
            val shared = setting("shared-name", "valueB", String.serializer())
        }

        val server = object : ServerBuilder() {
            val a = path include moduleA
            val b = path include moduleB
        }

        val definition = server.build()

        // Should only have one setting with that name (first one wins due to distinctBy)
        val sharedSettings = definition.settings.filter { it.name == "shared-name" }
        assertEquals(1, sharedSettings.size, "Duplicate setting names should be deduplicated")
    }

    // ==================== MediaType Coder Tests ====================

    @Test
    fun `registerBasicMediaTypeCoders adds encoders and decoders`() {
        val server = object : ServerBuilder() {
            init {
                registerBasicMediaTypeCoders()
            }
            val root = path.get bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
        }

        val definition = server.build()

        assertTrue(definition.mediaTypeEncoders.isNotEmpty(), "Should have media type encoders")
        assertTrue(definition.mediaTypeDecoders.isNotEmpty(), "Should have media type decoders")
    }

    // ==================== Compiled Interceptor Tests ====================

    @Test
    fun `compiledHttpInterceptors is lazily initialized`() {
        val server = object : ServerBuilder() {
            val root = path.get bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
        }

        val definition = server.build()

        // Just accessing should work without throwing
        val compiled = definition.compiledHttpInterceptors
        assertNotNull(compiled)
    }

    @Test
    fun `compiledWebsocketInterceptors is lazily initialized`() {
        val server = object : ServerBuilder() {
            val root = path.get bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
        }

        val definition = server.build()

        // Just accessing should work without throwing
        val compiled = definition.compiledWebsocketInterceptors
        assertNotNull(compiled)
    }

    // ==================== Server Definition entries Sequence Tests ====================

    @Test
    fun `entries returns all registered endpoints`() {
        val server = object : ServerBuilder() {
            val root = path.get bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
            val users = path.path("users").get bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
            val usersPost = path.path("users").post bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
        }

        val definition = server.build()
        val entries = definition.endpoints.entries.toList()

        // Should have 2 path entries (root and users), with users having 2 methods
        assertEquals(2, entries.size, "Should have 2 path entries")
    }

    // ==================== Server Definition Flattening Edge Cases ====================

    @Test
    fun `empty modules do not cause issues`() {
        val emptyModule = object : ServerBuilder() {}

        val server = object : ServerBuilder() {
            val empty = path.path("empty") include emptyModule
            val real = path.get bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
        }

        // Should not throw
        val definition = server.build()
        assertNotNull(definition)
        assertNotNull(definition.endpoints[PathSpec.root]?.http?.get(HttpMethod.GET))
    }

    @Test
    fun `deeply nested modules flatten correctly`() {
        val level3 = object : ServerBuilder() {
            val deep = path.path("deep").get bind HttpHandler<PathSpec0> {
                HttpResponse(status = HttpStatus.OK)
            }
        }

        val level2 = object : ServerBuilder() {
            val l3 = path.path("l3") include level3
        }

        val level1 = object : ServerBuilder() {
            val l2 = path.path("l2") include level2
        }

        val root = object : ServerBuilder() {
            val l1 = path.path("l1") include level1
        }

        val definition = root.build()

        assertNotNull(
            definition.endpoints[PathSpec.root.path("l1").path("l2").path("l3").path("deep")]?.http?.get(HttpMethod.GET),
            "Should have deeply nested endpoint at /l1/l2/l3/deep"
        )
    }
}
