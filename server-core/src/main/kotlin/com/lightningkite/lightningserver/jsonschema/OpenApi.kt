package com.lightningkite.lightningserver.jsonschema

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.typed.ApiEndpoint
import com.lightningkite.lightningserver.typed.Documentable
import com.lightningkite.lightningserver.typed.docGroup
import com.lightningkite.lightningserver.typed.functionName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement

@Serializable
data class OpenApiRoot(
    val openapi: String,
    val info: OpenApiInfo,
    val paths: Map<String, OpenApiPath>,
    val components: OpenApiComponents,
    val servers: List<OpenApiServer>,
    val security: List<Map<String, List<String>>>,
)

@Serializable
data class OpenApiSecurity(
    val type: OpenApiSecurityType,
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
    val url: String,
    val description: String,
)

@Serializable
data class OpenApiComponents(
    val schemas: Map<String, JsonSchemaType>,
    val securitySchemes: Map<String, OpenApiSecurity>,
)

@Serializable
data class OpenApiPath(
    val parameters: List<OpenApiParameter>,
    val get: OpenApiOperation? = null,
    val post: OpenApiOperation? = null,
    val put: OpenApiOperation? = null,
    val patch: OpenApiOperation? = null,
    val delete: OpenApiOperation? = null,
)

@Serializable
data class OpenApiOperation(
    val summary: String,
    val description: String,
    val tags: List<String>,
    val operationId: String,
    val parameters: List<OpenApiParameter> = listOf(),
    val requestBody: OpenApiRequestBody? = null,
    val responses: Map<String, OpenApiResponse>,
)

@Serializable
data class OpenApiRequestBody(
    val description: String? = null,
    val content: Map<String, OpenApiMediaType>,
    val required: Boolean,
)

@Serializable
data class OpenApiMediaType(
    val schema: JsonSchemaType,
    val examples: Map<String, OpenApiExample?>,
)

@Serializable
data class OpenApiExample(
    val summary: String,
    val description: String,
    val value: JsonElement,
)

@Serializable
data class OpenApiResponse(
    val description: String,
    val content: Map<String, OpenApiMediaType>,
)

@Serializable
data class OpenApiParameter(
    val name: String,
    @SerialName("in") val inside: OpenApiParameterType,
    val schema: JsonSchemaType,
    val description: String,
    val required: Boolean,
    val allowEmptyValue: Boolean,
)

@Serializable
enum class OpenApiParameterType {
    query, header, path, cookie
}

@Serializable
data class OpenApiInfo(
    val title: String,
    val version: String,
    val description: String? = null,
    val termsOfService: String? = null,
    val contact: OpenApiContact? = null,
    val license: OpenApiLicense? = null,
)

@Serializable
data class OpenApiLicense(
    val name: String,
    val url: String,
)

@Serializable
data class OpenApiContact(
    val name: String,
    val url: String,
    val email: String,
)

private fun <USER, INPUT : Any, OUTPUT> ApiEndpoint<USER, INPUT, OUTPUT>.openApi(builder: JsonSchemaBuilder): OpenApiOperation =
    OpenApiOperation(
        summary = (this.docGroup?.let { it.humanize() + " " } ?: "") + " - " + summary,
        description = description,
        tags = listOfNotNull(this.docGroup),
        operationId = (this.docGroup ?: "") + "_" + this.functionName,
        parameters = listOf(),
        requestBody = if (this.route.method == HttpMethod.GET) null else if (this.inputType == Unit.serializer()) null else OpenApiRequestBody(
            content = mapOf(
                ContentType.Application.Json.toString() to OpenApiMediaType(
                    schema = builder[this.inputType],
                    examples = mapOf()
                )
            ),
            required = true
        ),
        responses = mapOf(
            successCode.code.toString() to (if (this.inputType == Unit.serializer()) OpenApiResponse(
                "Success",
                mapOf()
            ) else OpenApiResponse(
                description = "Success",
                content = mapOf(
                    ContentType.Application.Json.toString() to OpenApiMediaType(
                        schema = builder[this.outputType],
                        examples = mapOf()
                    )
                )
            ))
            // TODO: Error codes
        )
    )

val openApiDescription: OpenApiRoot by lazy {
    val builder = JsonSchemaBuilder(Serialization.json, "#/components/schemas/", useNullableProperty = true)
    Documentable.endpoints.flatMap {
        sequenceOf(it.inputType, it.outputType) + it.routeTypes.values.asSequence()
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
        paths = Documentable.endpoints.filter { it.route.method != HttpMethod.GET || it.inputType == Unit.serializer() }.groupBy {
            it.path.toString()
        }.mapValues {
            OpenApiPath(
                parameters = it.value.first().routeTypes.map {
                    OpenApiParameter(
                        name = it.key,
                        inside = OpenApiParameterType.path,
                        description = it.key,
                        required = true,
                        schema = builder[it.value],
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