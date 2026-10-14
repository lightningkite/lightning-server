package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.execute
import com.lightningkite.lightningserver.runtime.test.test
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

class AuthEventTest {

    private class Throwing : AuthEventListener {
        context(runtime: ServerRuntime)
        override suspend fun onEvent(event: AuthEvent): Unit = throw IllegalStateException("listener broke")
    }

    private class Capturing : AuthEventListener {
        val events = mutableListOf<AuthEvent>()

        context(runtime: ServerRuntime)
        override suspend fun onEvent(event: AuthEvent) {
            events += event
        }
    }

    private object TestServer : ServerBuilder() {
        init {
            install(Throwing())
        }

        val capturing = install(Capturing())
    }

    @Test
    fun `a failing listener neither throws nor stops later listeners`() = runBlocking {
        TestServer.test({}) {
            val event = AuthEvent.TokenRejected(AuthEvent.TokenRejected.Reason.Malformed)

            execute { serverRuntime.reportAuthEvent(event) }

            assertEquals(listOf<AuthEvent>(event), TestServer.capturing.events)
        }
    }
}
