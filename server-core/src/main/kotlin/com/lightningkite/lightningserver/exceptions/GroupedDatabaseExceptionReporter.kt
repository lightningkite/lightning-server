package com.lightningkite.lightningserver.exceptions

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.RequestAuth
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.db.ModelInfoWithDefault
import com.lightningkite.lightningserver.db.ModelRestEndpoints
import com.lightningkite.lightningserver.db.ModelSerializationInfo
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.typed.AuthAccessor
import com.lightningkite.now
import kotlinx.serialization.serializer
import java.net.NetworkInterface

class GroupedDatabaseExceptionReporter(val packageName: String, val database: Database): ExceptionReporter {
    init {
        prepareModels()
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
                ?: NetworkInterface.getNetworkInterfaces().toList().sortedBy { it.name }
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
    val modelInfo = object: ModelInfoWithDefault<HasId<*>, ReportedExceptionGroup, Int> {
        override val serialization = ModelSerializationInfo<ReportedExceptionGroup, Int>(
            serializer = Serialization.module.serializer(),
            idSerializer = Serialization.module.serializer()
        )
        override val authOptions: AuthOptions<HasId<*>> get() = Authentication.isDeveloper as AuthOptions<HasId<*>>
        override fun baseCollection(): FieldCollection<ReportedExceptionGroup> = database.collection<ReportedExceptionGroup>()
        override fun collection(): FieldCollection<ReportedExceptionGroup> = baseCollection()

        override suspend fun collection(auth: AuthAccessor<HasId<*>>): FieldCollection<ReportedExceptionGroup> = collection()

        override suspend fun defaultItem(auth: RequestAuth<HasId<*>>?): ReportedExceptionGroup {
            return ReportedExceptionGroup(_id = 0, context = "", server = "", message = "", trace = "")
        }

        override fun exampleItem(): ReportedExceptionGroup? {
            return ReportedExceptionGroup(_id = 0, context = "", server = "", message = "", trace = "")
        }
    }

    val rest = ModelRestEndpoints(ServerPath("meta/exceptions"), modelInfo)
}