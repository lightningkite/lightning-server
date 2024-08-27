package com.lightningkite.lightningserver.exceptions

import com.lightningkite.prepareModelsServerCore
import com.lightningkite.lightningdb.*
import com.lightningkite.serialization.*
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.db.ModelRestEndpoints
import com.lightningkite.lightningserver.db.ModelSerializationInfo
import com.lightningkite.lightningserver.db.modelInfoWithDefault
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.now
import com.lightningkite.serialization.contextualSerializerIfHandled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.NetworkInterface

class GroupedDatabaseExceptionReporter(val packageName: String, val database: Database): ExceptionReporter {
    init {
        prepareModelsServerCore()
    }
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
                ?: withContext(Dispatchers.IO) {
                    NetworkInterface.getNetworkInterfaces()
                }.toList().sortedBy { it.name }
                    .firstOrNull()?.hardwareAddress?.sumOf { it.hashCode() }?.toString(16) ?: "?"
            val message = t.message ?: "No message"
            val trace = t.stackTraceToString()
            val now = now()
            modelInfo.collection().upsertOneIgnoringResult(
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


    @Suppress("UNCHECKED_CAST")
    val modelInfo = modelInfoWithDefault(
        serialization = ModelSerializationInfo<ReportedExceptionGroup, Int>(
            serializer = Serialization.module.contextualSerializerIfHandled(),
            idSerializer = Serialization.module.contextualSerializerIfHandled()
        ),
        authOptions = Authentication.isDeveloper as AuthOptions<HasId<*>>,
        getBaseCollection = { database.collection<ReportedExceptionGroup>() },
        exampleItem = { ReportedExceptionGroup(_id = 0, context = "", server = "", message = "", trace = "")},
        defaultItem = { ReportedExceptionGroup(_id = 0, context = "", server = "", message = "", trace = "") }
    )

    val rest = ModelRestEndpoints(ServerPath("meta/exceptions"), modelInfo)
}