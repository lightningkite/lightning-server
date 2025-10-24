package com.lightningkite.lightningserver.terraform.awsserverless

import com.lightningkite.services.otel.OpenTelemetrySettings
import com.lightningkite.services.terraform.TerraformJsonObject
import com.lightningkite.services.terraform.TerraformNeed
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonPrimitive
import software.amazon.awssdk.services.lambda.model.Architecture

context(emitter: TerraformAwsServerlessBuilder<*>) public fun TerraformNeed<OpenTelemetrySettings?>.otelDatadog(
    version: Int = 88
): Unit {
    val ext = if(emitter.architecture == Architecture.ARM64) "-ARM" else ""
    emitter.lambdaLayers += "arn:aws:lambda:${emitter.region.id()}:464622532012:layer:Datadog-Extension$ext:$version"
    emitter.variable(object: TerraformNeed<String> {
        override val name: String = "datadog_api_key"
        override val serializer: KSerializer<String> = String.serializer()
        override val default: String? = null
        override val instructions: String = "Go get an API key from DataDog!"
    })
    emitter.emit("variables") {
        "variable.datadog_api_key" {}
    }
    emitter.lambdaEnvironment["DD_API_KEY"] = "\${var.datadog_api_key}"
    emitter.lambdaEnvironment["DD_ENV"] = emitter.projectPrefix
    emitter.lambdaEnvironment["DD_SITE"] = "datadoghq.com"
    emitter.lambdaEnvironment["DD_SERVICE"] = emitter.handler.qualifiedName!!
    emitter.lambdaEnvironment["DD_VERSION"] = "1.0.0"
    emitter.fulfillSetting(name, JsonPrimitive("otel-grpc://localhost:4317"))
}
