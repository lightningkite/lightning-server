package com.lightningkite.lightningserver.jsonschema

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.titleCase
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.typed.ApiEndpoint
import com.lightningkite.lightningserver.typed.Documentable
import com.lightningkite.lightningserver.typed.docGroup
import com.lightningkite.lightningserver.typed.functionName
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

@Serializable
data class OpenApiRoot(
    val openapi: String,
    val info: OpenApiInfo = OpenApiInfo(),
    val paths: Map<String, OpenApiPath> = mapOf(),
    val components: OpenApiComponents = OpenApiComponents(),
    val servers: List<OpenApiServer> = listOf(),
    val security: List<Map<String, List<String>>> = listOf(),
)

@Serializable
data class OpenApiSecurity(
    val type: OpenApiSecurityType = OpenApiSecurityType.apiKey,
    val description: String? = null,
    val name: String? = null,
    @SerialName("in") val inside: OpenApiParameterType? = null,
    val scheme: String? = null,
    val bearerFormat: String? = null,
)

@Serializable
enum class OpenApiSecurityType {
    apiKey, http, oauth2, openIdConnect
}

@Serializable
data class OpenApiServer(
    val url: String = "",
    val description: String = "",
)

@Serializable
data class OpenApiComponents(
    val schemas: Map<String, JsonSchemaType> = mapOf(),
    val securitySchemes: Map<String, OpenApiSecurity> = mapOf(),
)

@Serializable
data class OpenApiPath(
    val parameters: List<OpenApiParameter> = listOf(),
    val get: OpenApiOperation? = null,
    val post: OpenApiOperation? = null,
    val put: OpenApiOperation? = null,
    val patch: OpenApiOperation? = null,
    val delete: OpenApiOperation? = null,
)

@Serializable
data class OpenApiOperation(
    val summary: String = "",
    val description: String = "",
    val tags: List<String> = listOf(),
    val operationId: String = "",
    val parameters: List<OpenApiParameter> = listOf(),
    val requestBody: OpenApiRequestBody? = null,
    val responses: Map<String, OpenApiResponse> = mapOf(),
)

@Serializable
data class OpenApiRequestBody(
    val description: String? = null,
    val content: Map<String, OpenApiMediaType> = mapOf(),
    val required: Boolean = false,
)

@Serializable
data class OpenApiMediaType(
    val schema: JsonSchemaType,
    val example: JsonElement = JsonNull,
    val examples: Map<String, OpenApiExample?> = mapOf(),
)

@Serializable
data class OpenApiExample(
    val summary: String = "",
    val description: String = "",
    val value: JsonElement = JsonNull,
)

@Serializable
data class OpenApiResponse(
    val description: String = "",
    val content: Map<String, OpenApiMediaType> = mapOf(),
)

@Serializable
data class OpenApiParameter(
    val name: String,
    @SerialName("in") val inside: OpenApiParameterType = OpenApiParameterType.cookie,
    val schema: JsonSchemaType = JsonSchemaType(),
    val description: String = "",
    val required: Boolean = false,
    val allowEmptyValue: Boolean = false,
)

@Serializable
enum class OpenApiParameterType {
    query, header, path, cookie
}

@Serializable
data class OpenApiInfo(
    val title: String = "",
    val version: String = "",
    val description: String? = null,
    val termsOfService: String? = null,
    val contact: OpenApiContact? = null,
    val license: OpenApiLicense? = null,
)

@Serializable
data class OpenApiLicense(
    val name: String = "",
    val url: String = "",
)

@Serializable
data class OpenApiContact(
    val name: String = "",
    val url: String = "",
    val email: String = "",
)

private fun <T> make(type: KSerializer<T>, item: T): Map<String, OpenApiExample> {
    return mapOf(
        "application/json" to OpenApiExample(
            value = Serialization.json.encodeToJsonElement(type, item)
        ),
//        "text/csv" to OpenApiExample(
//            value = JsonPrimitive(Serialization.csv.encodeToString(type, item))
//        ),
//        ContentType.Application.FormUrlEncoded.toString() to OpenApiExample(
//            value = JsonPrimitive(Serialization.properties.encodeToFormData(type, item))
//        )
    )
}

private fun ApiEndpoint<*, *, *, *>.openApi(builder: JsonSchemaBuilder): OpenApiOperation =
    OpenApiOperation(
        summary = (this.docGroup?.let { it.titleCase() + " " } ?: "") + " - " + summary,
        description = description,
        tags = listOfNotNull(this.docGroup),
        operationId = (this.docGroup ?: "") + "_" + this.functionName,
        parameters = listOf(),
        requestBody = if (this.route.method == HttpMethod.GET) null else if (this.inputType == Unit.serializer()) null else OpenApiRequestBody(
            content = mapOf(
                ContentType.Application.Json.toString() to OpenApiMediaType(
                    schema = builder[this.inputType],
                    example = examples.firstOrNull()
                        ?.let { example -> Serialization.json.encodeToJsonElement(inputType as KSerializer<Any?>, example.input) }
                        ?: JsonNull,
//                    examples = examples.groupBy { it.name }.flatMap {
//                        if (it.value.size == 1) it.value else it.value.mapIndexed { index, it ->
//                            it.copy(
//                                name = it.name + " " + index.plus(1)
//                            )
//                        }
//                    }.associate { example ->
//                        example.name to OpenApiExample(
//                            example.name,
//                            value = Serialization.json.encodeToJsonElement(inputType, example.input)
//                        )
//                    }
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
                    ContentType.Application.Json.toString() to OpenApiMediaType(
                        schema = builder[this.outputType],
                        example = examples.firstOrNull()
                            ?.let { example -> Serialization.json.encodeToJsonElement(outputType as KSerializer<Any?>, example.output) }
                            ?: JsonNull,
//                        examples = examples.groupBy { it.name }.flatMap {
//                            if (it.value.size == 1) it.value else it.value.mapIndexed { index, it ->
//                                it.copy(name = it.name + " " + index.plus(1))
//                            }
//                        }.associate { example ->
//                            example.name to OpenApiExample(
//                                example.name,
//                                value = Serialization.json.encodeToJsonElement(outputType, example.output)
//                            )
//                        }
                    )
                )
            ))
            // TODO: Error codes
        )
    )

val openApiDescription: OpenApiRoot by lazy {
    val builder = JsonSchemaBuilder(Serialization.json, "#/components/schemas/", useNullableProperty = true)
    Documentable.endpoints.flatMap {
        sequenceOf(it.inputType, it.outputType) + it.route.path.serializers.asSequence()
    }.distinct().forEach { builder.get(it) }

    OpenApiRoot(
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
        paths = Documentable.endpoints.filter { it.route.method != HttpMethod.GET || it.inputType == Unit.serializer() }
            .groupBy {
                it.path.path.toString()
            }.mapValues {
                OpenApiPath(
                    parameters = it.value.first().path.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>().zip(
                        it.value.first().route.path.serializers
                    ) { seg, ser ->
                        val name = seg.name
                        OpenApiParameter(
                            name = name,
                            inside = OpenApiParameterType.path,
                            description = name,
                            required = true,
                            schema = builder[ser],
                            allowEmptyValue = false
                        )
                    },
                    get = it.value.find { it.route.method == HttpMethod.GET }?.openApi(builder),
                    post = it.value.find { it.route.method == HttpMethod.POST }?.openApi(builder),
                    put = it.value.find { it.route.method == HttpMethod.PUT }?.openApi(builder),
                    patch = it.value.find { it.route.method == HttpMethod.PATCH }?.openApi(builder),
                    delete = it.value.find { it.route.method == HttpMethod.DELETE }?.openApi(builder),
                )
            }
    )
}