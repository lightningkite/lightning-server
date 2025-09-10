package com.lightningkite.lightningserver.typed.jsonschema

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.ApiWebsocketHandler
import com.lightningkite.lightningserver.typed.docGroup
import com.lightningkite.lightningserver.typed.locationedApiHttpHandlers
import com.lightningkite.lightningserver.typed.functionName
import com.lightningkite.lightningserver.typed.jsonschema.openApi
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.default
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

@Serializable
public data class OpenApiRoot(
    val openapi: String,
    val info: OpenApiInfo = OpenApiInfo(),
    val paths: Map<String, OpenApiPath> = mapOf(),
    val components: OpenApiComponents = OpenApiComponents(),
    val servers: List<OpenApiServer> = listOf(),
    val security: List<Map<String, List<String>>> = listOf(),
)

@Serializable
public data class OpenApiSecurity(
    val type: OpenApiSecurityType = OpenApiSecurityType.apiKey,
    val description: String? = null,
    val name: String? = null,
    @SerialName("in") val inside: OpenApiParameterType? = null,
    val scheme: String? = null,
    val bearerFormat: String? = null,
)

@Serializable
public enum class OpenApiSecurityType {
    apiKey, http, oauth2, openIdConnect
}

@Serializable
public data class OpenApiServer(
    val url: String = "",
    val description: String = "",
)

@Serializable
public data class OpenApiComponents(
    val schemas: Map<String, JsonSchemaType> = mapOf(),
    val securitySchemes: Map<String, OpenApiSecurity> = mapOf(),
)

@Serializable
public data class OpenApiPath(
    val parameters: List<OpenApiParameter> = listOf(),
    val get: OpenApiOperation? = null,
    val post: OpenApiOperation? = null,
    val put: OpenApiOperation? = null,
    val patch: OpenApiOperation? = null,
    val delete: OpenApiOperation? = null,
)

@Serializable
public data class OpenApiOperation(
    val summary: String = "",
    val description: String = "",
    val tags: List<String> = listOf(),
    val operationId: String = "",
    val parameters: List<OpenApiParameter> = listOf(),
    val requestBody: OpenApiRequestBody? = null,
    val responses: Map<String, OpenApiResponse> = mapOf(),
)

@Serializable
public data class OpenApiRequestBody(
    val description: String? = null,
    val content: Map<String, OpenApiMediaType> = mapOf(),
    val required: Boolean = false,
)

@Serializable
public data class OpenApiMediaType(
    val schema: JsonSchemaType,
    val example: JsonElement = JsonNull,
    val examples: Map<String, OpenApiExample?> = mapOf(),
)

@Serializable
public data class OpenApiExample(
    val summary: String = "",
    val description: String = "",
    val value: JsonElement = JsonNull,
)

@Serializable
public data class OpenApiResponse(
    val description: String = "",
    val content: Map<String, OpenApiMediaType> = mapOf(),
)

@Serializable
public data class OpenApiParameter(
    val name: String,
    @SerialName("in") val inside: OpenApiParameterType = OpenApiParameterType.cookie,
    val schema: JsonSchemaType = JsonSchemaType(),
    val description: String = "",
    val required: Boolean = false,
    val allowEmptyValue: Boolean = false,
)

@Serializable
public enum class OpenApiParameterType {
    query, header, path, cookie
}

@Serializable
public data class OpenApiInfo(
    val title: String = "",
    val version: String = "",
    val description: String? = null,
    val termsOfService: String? = null,
    val contact: OpenApiContact? = null,
    val license: OpenApiLicense? = null,
)

@Serializable
public data class OpenApiLicense(
    val name: String = "",
    val url: String = "",
)

@Serializable
public data class OpenApiContact(
    val name: String = "",
    val url: String = "",
    val email: String = "",
)

context(runtime: ServerRuntime)
private fun <T> make(type: KSerializer<T>, item: T): Map<String, OpenApiExample> {
    return mapOf(
        "application/json" to OpenApiExample(
            value = runtime.externalSerialization.json.encodeToJsonElement(type, item)
        ),
//        "text/csv" to OpenApiExample(
//            value = JsonPrimitive(Serialization.csv.encodeToString(type, item))
//        ),
//        ContentType.Application.FormUrlEncoded.toString() to OpenApiExample(
//            value = JsonPrimitive(Serialization.properties.encodeToFormData(type, item))
//        )
    )
}

context(runtime: ServerRuntime)
private fun Locationed<HttpEndpoint<PathSpec>, ApiHttpHandler<*, *, *, *>>.openApiUntyped(builder: JsonSchemaBuilder): OpenApiOperation =
    (this as Locationed<HttpEndpoint<PathSpec>, ApiHttpHandler<PathSpec, HasId<*>?, Any?, Any?>>).openApi(builder)

context(runtime: ServerRuntime)
private fun <PATH : PathSpec, USER : HasId<*>?, INPUT, OUTPUT> Locationed<HttpEndpoint<PathSpec>, ApiHttpHandler<PATH, USER, INPUT, OUTPUT>>.openApi(
    builder: JsonSchemaBuilder
): OpenApiOperation = with(value) {
    val docGroup: String? = context(runtime.server) { key.path.docGroup }
    OpenApiOperation(
        summary = summary,
        description = description,
        tags = listOfNotNull(docGroup),
        operationId = (docGroup ?: "") + "_" + this.functionName,
        parameters = listOf(),
        requestBody = if (location.method == HttpMethod.GET) null else if (this.inputType == Unit.serializer()) null else OpenApiRequestBody(
            content = mapOf(
                MediaType.Application.Json.toString() to OpenApiMediaType(
                    schema = builder[this.inputType],
                    example = (examples.firstOrNull() ?: ApiHttpHandler.Example(
                        inputType.default(),
                        outputType.default()
                    ))
                        .let { example ->
                            @Suppress("UNCHECKED_CAST")
                            runtime.externalSerialization.json.encodeToJsonElement(
                                inputType as KSerializer<Any?>,
                                example.input
                            )
                        },
                    examples = examples.groupBy { it.name }.flatMap {
                        if (it.value.size == 1) it.value else it.value.mapIndexed { index, it ->
                            it.copy(
                                name = it.name + " " + index.plus(1)
                            )
                        }
                    }.associate { example ->
                        example.name to OpenApiExample(
                            example.name,
                            value = runtime.externalSerialization.json.encodeToJsonElement(inputType, example.input)
                        )
                    }
                )
            ),
            required = true
        ),
        responses = mapOf(
            successCode.code.toString() to (if (this.outputType == Unit.serializer()) OpenApiResponse(
                "Success",
                mapOf()
            ) else OpenApiResponse(
                description = "Success",
                content = mapOf(
                    MediaType.Application.Json.toString() to OpenApiMediaType(
                        schema = builder[this.outputType],
                        example = (examples.firstOrNull() ?: ApiHttpHandler.Example(
                            inputType.default(),
                            outputType.default()
                        ))
                            .let { example ->
                                @Suppress("UNCHECKED_CAST")
                                runtime.externalSerialization.json.encodeToJsonElement(
                                    outputType as KSerializer<Any?>,
                                    example.output
                                )
                            },
                        examples = examples.groupBy { it.name }.flatMap {
                            if (it.value.size == 1) it.value else it.value.mapIndexed { index, it ->
                                it.copy(name = it.name + " " + index.plus(1))
                            }
                        }.associate { example ->
                            example.name to OpenApiExample(
                                example.name,
                                value = runtime.externalSerialization.json.encodeToJsonElement(
                                    outputType,
                                    example.output
                                )
                            )
                        }
                    )
                )
            ))
            // TODO: Error codes
        )
    )
}

public context(runtime: ServerRuntime)
val openApiDescription: OpenApiRoot
    get() {
        val builder =
            JsonSchemaBuilder(runtime.externalSerialization.json, "#/components/schemas/", useNullableProperty = true)

        runtime.server.endpoints.entries.forEach { (path, endpoints) ->
            endpoints.http.entries.forEach { (method, handler) ->
                if (handler is ApiHttpHandler<*, *, *, *>) {
                    builder[handler.inputType]
                    builder[handler.outputType]
                }
                path.wildcards.forEach { builder[it.serializer] }
            }
            endpoints.websocket?.let { handler ->
                if (handler is ApiWebsocketHandler<*, *, *, *, *>) {
                    builder[handler.inputType]
                    builder[handler.outputType]
                }
            }
        }

        return OpenApiRoot(
            openapi = "3.0.2",
            info = OpenApiInfo(
                title = generalSettings().projectName,
                version = "current",
            ),
            components = OpenApiComponents(
                schemas = builder.definitions,
                securitySchemes = mapOf(
                    "header" to OpenApiSecurity(
                        type = OpenApiSecurityType.http,
                        description = "Authorization Header",
                        scheme = "bearer",
                        bearerFormat = "JWT",
                    ),
                    "param" to OpenApiSecurity(
                        type = OpenApiSecurityType.apiKey,
                        description = "Parameter",
                        name = "jwt",
                        inside = OpenApiParameterType.query
                    ),
                    "cookie" to OpenApiSecurity(
                        type = OpenApiSecurityType.apiKey,
                        description = "Cookie",
                        name = "Authorization",
                        inside = OpenApiParameterType.cookie
                    )
                )
            ),
            servers = listOf(
                OpenApiServer(url = generalSettings().publicUrl, description = "Current Server")
            ),
            security = listOf(
                mapOf(),
                mapOf("header" to listOf()),
                mapOf("param" to listOf()),
                mapOf("cookie" to listOf()),
            ),
            paths = runtime.server.locationedApiHttpHandlers
                .filter { it.location.method != HttpMethod.GET || it.value.inputType == Unit.serializer() }
                .groupBy { it.location.path.toString() }
                .entries
                .sortedBy { it.key }
                .associate {
                    it.key to OpenApiPath(
                        parameters = it.value.first().location.path.wildcards.map { seg ->
                            val name = seg.name
                            val ser = seg.serializer
                            OpenApiParameter(
                                name = name,
                                inside = OpenApiParameterType.path,
                                description = name,
                                required = true,
                                schema = builder[ser],
                                allowEmptyValue = false
                            )
                        },
                        get = it.value.find { it.location.method == HttpMethod.GET }?.openApiUntyped(builder),
                        post = it.value.find { it.location.method == HttpMethod.POST }?.openApiUntyped(builder),
                        put = it.value.find { it.location.method == HttpMethod.PUT }?.openApiUntyped(builder),
                        patch = it.value.find { it.location.method == HttpMethod.PATCH }?.openApiUntyped(builder),
                        delete = it.value.find { it.location.method == HttpMethod.DELETE }?.openApiUntyped(builder),
                    )
                }
        )
    }