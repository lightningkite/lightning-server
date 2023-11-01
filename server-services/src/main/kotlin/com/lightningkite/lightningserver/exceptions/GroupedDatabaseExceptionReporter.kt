package com.lightningkite.lightningserver.exceptions

import com.lightningkite.lightningdb.*
import com.lightningkite.now
import kotlinx.serialization.modules.SerializersModule
import java.net.NetworkInterface

class GroupedDatabaseExceptionReporter(val packageName: String, val database: Database): ExceptionReporter {
    init {
        prepareModels()
    }
    val collection = database.collection(SerializersModule {  }, ReportedExceptionGroup.serializer(), "ReportedExceptionGroup")
    override suspend fun report(t: Throwable, context: Any?): Boolean {
        try {
            var id = 0
            t.stackTrace
                .filter { it.className.startsWith(packageName) }
                .forEach {
                    id = id * 31 + it.className.hashCode()
                    id = id * 31 + it.lineNumber.hashCode()
                }
            val contextString = context.toString()
            val server = System.getenv("AWS_LAMBDA_LOG_STREAM_NAME")?.takeUnless { it.isEmpty() }
                ?: NetworkInterface.getNetworkInterfaces().toList().sortedBy { it.name }
                    .firstOrNull()?.hardwareAddress?.sumOf { it.hashCode() }?.toString(16) ?: "?"
            val message = t.message ?: "No message"
            val trace = t.stackTraceToString()
            val now = now()
            collection.upsertOneIgnoringResult(
                condition { it._id eq id },
                modification {
                    it.count += 1
                    it.context assign contextString
                    it.server assign server
                    it.message assign message
                    it.trace assign trace
                    it.lastOccurredAt assign now
                },
                ReportedExceptionGroup(
                    _id = id,
                    lastOccurredAt = now,
                    context = contextString,
                    server = server,
                    message = message,
                    trace = trace
                )
            )
            return true
        } catch(e: Exception) {
            return false
        }
    }
}