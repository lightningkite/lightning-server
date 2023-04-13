package com.lightningkite.lightningserver.exceptions

import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.logger
import com.lightningkite.lightningserver.typed.typed
import java.time.Instant

/**
 * An ExceptionReporter implementation that logs all reports out to a logger using debug and stores the most recent 100 exceptions.
 * An endpoint is added for retrieving the recent exceptions.
 * This is useful in a local development environment.
 */
object DebugExceptionReporter : ExceptionReporter {
    val previousErrors = ArrayList<Triple<Instant, Throwable, Any?>>()
    override suspend fun report(t: Throwable, context: Any?): Boolean {
        logger.debug(
            """
Exception Reported:
    ${t.message}:
        ${t.stackTraceToString()}
""".trimIndent()
        )
        previousErrors.add(Triple(Instant.now(), t, context))
        while (previousErrors.size > 100) previousErrors.removeAt(0)
        return true
    }

    val exceptionListEndpoint = ServerPath.root.path("exceptions").get.typed(
        summary = "List Recent Exceptions",
        description = "Lists the most recent 100 exceptions to have occurred on this server",
        errorCases = listOf(),
        implementation = { user: Unit, input: Unit ->
            previousErrors.map {
                Triple(
                    it.first,
                    it.second.stackTraceToString(),
                    it.third.toString()
                )
            }
        }
    )
}
