package com.lightningkite.lightningserver.aws

import com.lightningkite.lightningserver.exceptions.exceptionSettings
import com.lightningkite.lightningserver.exceptions.report
import com.lightningkite.lightningserver.metrics.Metrics
import com.lightningkite.lightningserver.serialization.AnonType
import com.lightningkite.lightningserver.serialization.InternalCommunicationEncoding
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.tasks.Task
import com.lightningkite.lightningserver.tasks.Tasks
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.lambda.model.InvocationType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class AwsAdapterTask(val root: AwsAdapter) {

    @Serializable
    data class TaskInvoke(val taskName: String, val input: AnonType)

    suspend fun launchTask(task: Task<Any?>, input: Any?) {
        try {
            root.lambdaClient.invoke {
                it.functionName(System.getenv("AWS_LAMBDA_FUNCTION_NAME"))
                it.qualifier(System.getenv("AWS_LAMBDA_FUNCTION_VERSION"))
                it.invocationType(InvocationType.EVENT)
                it.payload(
                    SdkBytes.fromUtf8String(
                        Serialization.Internal.json.encodeToString(
                            TaskInvoke.serializer(),
                            TaskInvoke(task.name, AnonType(input, task.serializer))
                        )
                    )
                )
            }.await().let {
                it.logResult()
            }
        } catch (e: Exception) {
            throw Exception("Failed to call ${task.name}", e)
        }
    }
    suspend fun handleTask(event: TaskInvoke): APIGatewayV2HTTPResponse {
        return coroutineScope {
            @Suppress("UNCHECKED_CAST") val task = Tasks.tasks[event.taskName] as Task<Any?>?
            if (task == null) {
                exceptionSettings().report(AwsTaskInvokeException("Task ${event.taskName} not found"), event.taskName)
                root.logger.error("Task ${event.taskName} not found")
                APIGatewayV2HTTPResponse(statusCode = 404, body = "Task ${event.taskName} not found")
            } else try {
                Metrics.handlerPerformance(task) {
                    task.invokeImmediate(this, event.input.value(task.serializer))
                }
                APIGatewayV2HTTPResponse(statusCode = 204)
            } catch (e: Exception) {
                e.report(task)
                APIGatewayV2HTTPResponse(statusCode = 500, body = e.message)
            }
        }
    }
    private class AwsTaskInvokeException(message: String? = null, cause: Exception? = null) : Exception(message, cause)
}