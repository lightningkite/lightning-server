package com.lightningkite.lightningserver.typed.jsonschema

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.plus
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.ApiWebSocketHandler
import com.lightningkite.lightningserver.typed.sdk.*
import com.lightningkite.lightningserver.typed.sdk.SDK.sdk
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.default
import kotlinx.serialization.*
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
private fun <PATH : PathSpec, USER : HasId<*>?, INPUT, OUTPUT> ApiHttpHandler<PATH, USER, INPUT, OUTPUT>.openApi(
    builder: JsonSchemaBuilder,
    method: HttpMethod,
    docGroup: String?,
) = OpenApiOperation(
    summary = summary,
    description = description,
    tags = listOfNotNull(docGroup),
    operationId = if (docGroup == null) functionName.functionCase() else (docGroup + "_" + functionName.functionCase()),
    parameters = listOf(),
    requestBody = if (method == HttpMethod.GET) null else if (this.inputType.isUnit()) null else OpenApiRequestBody(
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
    responses = run {
        val response =
            if (this.outputType.isUnit()) OpenApiResponse("Success", emptyMap())
            else OpenApiResponse(
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
            )

        // Declared error cases become documented responses keyed by status code. Cases sharing a status
        // code are grouped into one response with a combined description and the LSError schema/example.
        val errorResponses = errorCases
            .groupBy { it.http }
            .mapKeys { it.key.toString() }
            .mapValues { (_, cases) ->
                OpenApiResponse(
                    description = cases.joinToString("\n") { case ->
                        val prefix = if (case.detail.isNotBlank()) "[${case.detail}] " else ""
                        prefix + case.message.ifBlank { "Error" }
                    },
                    content = mapOf(
                        MediaType.Application.Json.toString() to OpenApiMediaType(
                            schema = builder[LSError.serializer()],
                            example = runtime.externalSerialization.json.encodeToJsonElement(
                                LSError.serializer(),
                                cases.first()
                            )
                        )
                    )
                )
            }

        mapOf(successCode.code.toString() to response) + errorResponses
    }
)

context(runtime: ServerRuntime)
public val openApiDescription: OpenApiRoot
    get() {
        val builder =
            JsonSchemaBuilder(runtime.externalSerialization.json, "#/components/schemas/", useNullableProperty = true)

        runtime.server.endpoints.forEach { (path, endpoints) ->
            path.wildcards.forEach { builder[it.serializer] }
            endpoints.http.entries.forEach { (_, handler) ->
                if (handler is ApiHttpHandler<*, *, *, *>) {
                    builder[handler.inputType]
                    builder[handler.outputType]
                }
            }
            endpoints.webSocket?.let { handler ->
                if (handler is ApiWebSocketHandler<*, *, *, *, *>) {
                    builder[handler.inputType]
                    builder[handler.outputType]
                }
            }
        }

        return OpenApiRoot(
            openapi = "3.0.2",
            info = OpenApiInfo(
                title = generalSettings().projectName,
                version = runtime.serverVersion
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
                mapOf("header" to emptyList()),
                mapOf("param" to emptyList()),
                mapOf("cookie" to emptyList()),
            ),
            paths = runtime.server
                .sdk()
                .filterSafeEndpoints()
                .asSequence()
                .flatMap { node ->
                    val docGroup = node.docGroup

                    node.layer.endpoints.flatMap { (_, pathSpecMap) ->
                        pathSpecMap.asSequence().map { (path, endpoints) ->
                            fun find(method: HttpMethod) = endpoints.http[method]?.openApi(builder, method, docGroup)

                            (node.absolutePath + path).toString() to OpenApiPath(
                                parameters = path.wildcards.map { segment ->
                                    OpenApiParameter(
                                        name = segment.name,
                                        inside = OpenApiParameterType.path,
                                        description = segment.name,
                                        required = true,
                                        schema = builder[segment.serializer],
                                        allowEmptyValue = false
                                    )
                                },
                                get = find(HttpMethod.GET),
                                post = find(HttpMethod.POST),
                                put = find(HttpMethod.PUT),
                                patch = find(HttpMethod.PATCH),
                                delete = find(HttpMethod.DELETE)
                            )
                        }
                    }
                }
                .toMap()
        )
    }