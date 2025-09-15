package com.lightningkite.lightningserver.engine.awsserverless

import com.lightningkite.lightningserver.AnonType
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.handleWithMetrics
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.services.data.KotlinBytesFormat
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.lambda.model.InvocationType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal class AwsAdapterTask(val root: AwsAdapter) {
    val json: Json get() = root.internalSerialization.json
    val format: KotlinBytesFormat get() = root.internalSerialization.kotlinBytesFormat

    @Serializable
    data class TaskInvoke(val taskName: String, val input: AnonType)

    suspend fun <T> launchTask(location: PathSpec0, task: Task<T>, input: T) {
        try {
            root.lambdaClient.invoke {
                it.functionName(System.getenv("AWS_LAMBDA_FUNCTION_NAME"))
                it.qualifier(System.getenv("AWS_LAMBDA_FUNCTION_VERSION"))
                it.invocationType(InvocationType.EVENT)
                it.payload(
                    SdkBytes.fromUtf8String(
                        json.encodeToString(
                            TaskInvoke.serializer(),
                            TaskInvoke(location.toString(), AnonType(format, input, task.serializer))
                        )
                    )
                )
            }.await().let {
                it.logResult()
            }
        } catch (e: Exception) {
            throw Exception("Failed to call ${task}", e)
        }
    }
    suspend fun handleTask(event: TaskInvoke): APIGatewayV2HTTPResponse {
        return coroutineScope {
            val p = PathSpec0(event.taskName)
            val task = root.server.tasks[p]
            if (task == null) {
                with(root) { exceptionSettings() }.report(AwsTaskInvokeException("Task ${event.taskName} not found"), event.taskName)
                root.logger.error("Task ${event.taskName} not found")
                APIGatewayV2HTTPResponse(statusCode = 404, body = "Task ${event.taskName} not found")
            } else try {
                @Suppress("UNCHECKED_CAST")
                task as Task<Any?>
                with(root) {
                    task.handleWithMetrics(p, event.input.value(root.internalSerialization.kotlinBytesFormat, task.serializer))
                }
                APIGatewayV2HTTPResponse(statusCode = 204)
            } catch (e: Exception) {
                APIGatewayV2HTTPResponse(statusCode = 500, body = e.message)
            }
        }
    }
    private class AwsTaskInvokeException(message: String? = null, cause: Exception? = null) : Exception(message, cause)
}