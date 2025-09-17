package com.lightningkite.lightningserver.engine.awsserverless

import com.lightningkite.lightningserver.AnonType
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.executeWithMetrics
import com.lightningkite.services.data.KotlinBytesFormat
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.lambda.model.InvocationType
import software.amazon.awssdk.services.lambda.model.InvokeRequest

internal class AwsAdapterTask(val root: AwsAdapter) {
    val json: Json get() = root.internalSerialization.json
    val format: KotlinBytesFormat get() = root.internalSerialization.kotlinBytesFormat

    @Serializable
    data class TaskInvoke(val taskName: String, val input: AnonType): AwsLambdaInput

    suspend fun <T> launchTask(location: PathSpec0, task: Task<T>, input: T) {
        try {
            root.invokeLambda(InvokeRequest.builder().also {
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
            }.build()).logResult()
        } catch (e: Exception) {
            throw Exception("Failed to call ${task}", e)
        }
    }
    suspend fun handleTask(event: TaskInvoke): APIGatewayV2HTTPResponse {
        return coroutineScope {
            val p = PathSpec0.fromString(event.taskName)
            val task = root.server.tasks[p]
            if (task == null) {
                root.logger.error("Task ${event.taskName} not found")
                APIGatewayV2HTTPResponse(statusCode = 404, body = "Task ${event.taskName} not found")
            } else try {
                @Suppress("UNCHECKED_CAST")
                task as Task<Any?>
                with(root) {
                    task.executeWithMetrics(
                        p,
                        event.input.value(root.internalSerialization.kotlinBytesFormat, task.serializer)
                    )
                }
                APIGatewayV2HTTPResponse(statusCode = 204)
            } catch (e: Exception) {
                APIGatewayV2HTTPResponse(statusCode = 500, body = e.message)
            }
        }
    }
    private class AwsTaskInvokeException(message: String? = null, cause: Exception? = null) : Exception(message, cause)
}