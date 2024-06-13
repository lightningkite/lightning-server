package com.lightningkite.lightningserver.exceptions

import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.logger
import com.lightningkite.lightningserver.typed.api
import com.lightningkite.lightningserver.typed.typed
import com.lightningkite.now
import kotlinx.datetime.Instant
import java.net.NetworkInterface

/**
 * An ExceptionReporter implementation that logs all reports out to a logger using debug and stores the most recent 100 exceptions.
 * An endpoint is added for retrieving the recent exceptions.
 * This is useful in a local development environment.
 */
object DebugExceptionReporter : ExceptionReporter {
    val previousErrors = ArrayList<Triple<Instant, Throwable, Any?>>()
    val server: String = System.getenv("AWS_LAMBDA_LOG_STREAM_NAME")?.takeUnless { it.isEmpty() }
        ?: NetworkInterface.getNetworkInterfaces().toList().sortedBy { it.name }
            .firstOrNull()?.hardwareAddress?.sumOf { it.hashCode() }?.toString(16) ?: "?"

    override suspend fun report(t: Throwable, context: Any?): Boolean {
        logger.debug(
            """
Exception Reported:
    Context: $context
    Message: ${t.message}:
        ${t.stackTraceToString()}
""".trimIndent()
        )
        previousErrors.add(Triple(now(), t, context))
        while (previousErrors.size > 100) previousErrors.removeAt(0)
        return true
    }

    val exceptionListEndpoint = ServerPath.root.path("exceptions").get.api(
        summary = "List Recent Exceptions",
        description = "Lists the most recent 100 exceptions to have occurred on this server",
        errorCases = listOf(),
        authOptions = Authentication.isDeveloper,
        implementation = {  _: Unit ->
            previousErrors
                .sortedBy { it.first }
                .mapIndexed { index, it ->
                    ReportedExceptionGroup(
                        _id = index,
                        lastOccurredAt = it.first,
                        context = it.third.toString(),
                        message = it.second.message ?: "",
                        trace = it.second.stackTraceToString(),
                        server = server,
                    )
                }
        }
    )
}
