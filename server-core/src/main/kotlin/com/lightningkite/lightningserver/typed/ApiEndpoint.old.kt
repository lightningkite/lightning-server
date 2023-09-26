package com.lightningkite.lightningserver.typed

import java.time.Duration
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.reflect.typeOf

/**
 * Builds a typed route.
 */
@JvmName("typedDirect")
@LightningServerDsl
@Deprecated("Use the new `.api` instead.")
inline fun <reified USER, reified INPUT : Any, reified OUTPUT> HttpEndpoint.typed(
    summary: String,
    description: String = summary,
    scopes: Set<String>? = null,
    maxAge: Duration? = null,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    crossinline implementation: suspend (user: USER, input: INPUT) -> OUTPUT
) = typed(
    authOptions = if (USER::class == kotlin.Unit::class) com.lightningkite.lightningserver.auth.noAuth else AuthOptions<HasId<*>?>(
        buildSet {
            val type = typeOf<USER>()
            if (type.isMarkedNullable) add(null)
            add(
                AuthOption(
                    AuthType(type),
                    scopes = scopes,
                    maxAge = maxAge
                )
            )
        }
    ),
    inputType = Serialization.module.serializer(),
    outputType = Serialization.module.serializer(),

    summary = summary,
    description = description,
    errorCases = errorCases,
    examples = examples,
    successCode = successCode,
    implementation = { user: USER, input: INPUT ->
        implementation(user, input)
    }
)

/**
 * Builds a typed route.
 */
@JvmName("typedDirect")
@LightningServerDsl
@Deprecated("Use the new `.api` instead.")
fun <USER, INPUT : Any, OUTPUT> HttpEndpoint.typed(
    authOptions: AuthOptions<*>,
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,

    summary: String,
    description: String = summary,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    implementation: suspend (user: USER, input: INPUT) -> OUTPUT
): ApiEndpoint<HasId<*>?, TypedServerPath0, INPUT, OUTPUT> =
    TypedHttpEndpoint(TypedServerPath0(this.path), this.method).api(
        authOptions = authOptions,
        inputType = inputType,
        outputType = outputType,
        summary = summary,
        description = description,
        errorCases = errorCases,
        examples = examples,
        successCode = successCode,
        implementation = { input: INPUT ->
            implementation(if(authOptions == noAuth) Unit as USER else this.user() as USER, input)
        }
    )

/**
 * Builds a typed route.
 */
@JvmName("typedDirect")
@LightningServerDsl
@Deprecated("Use the new `.api` instead.")
inline fun <reified USER, reified PATH, reified INPUT : Any, reified OUTPUT> HttpEndpoint.typed(
    summary: String,
    description: String = summary,
    scopes: Set<String>? = null,
    maxAge: Duration? = null,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    crossinline implementation: suspend (user: USER, route: PATH, input: INPUT) -> OUTPUT
) = typed(
    authOptions = if (USER::class == kotlin.Unit::class) com.lightningkite.lightningserver.auth.noAuth else AuthOptions<HasId<*>?>(
        buildSet {
            val type = typeOf<USER>()
            if (type.isMarkedNullable) add(null)
            add(
                AuthOption(
                    AuthType(type),
                    scopes = scopes,
                    maxAge = maxAge
                )
            )
        }
    ),
    inputType = Serialization.module.serializer(),
    outputType = Serialization.module.serializer(),
    pathType = Serialization.module.serializer(),

    summary = summary,
    description = description,
    errorCases = errorCases,
    examples = examples,
    successCode = successCode,
    implementation = { user: USER, path: PATH, input: INPUT -> implementation(user, path, input) }
)

/**
 * Builds a typed route.
 */
@JvmName("typedDirect")
@LightningServerDsl
@Deprecated("Use the new `.api` instead.")
fun <USER, PATH, INPUT : Any, OUTPUT> HttpEndpoint.typed(
    authOptions: AuthOptions<*>,
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,
    pathType: KSerializer<PATH>,
    summary: String,
    description: String = summary,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    implementation: suspend (user: USER, route: PATH, input: INPUT) -> OUTPUT
): ApiEndpoint<HasId<*>?, TypedServerPath1<PATH>, INPUT, OUTPUT> = TypedHttpEndpoint(
    TypedServerPath1(this.path, TypedServerPathParameter(path.segments.filterIsInstance<ServerPath.Segment.Wildcard>()[0].name, null, pathType)),
    this.method
).api<HasId<*>?, TypedServerPath1<PATH>, INPUT, OUTPUT>(
    authOptions = authOptions,
    inputType = inputType,
    outputType = outputType,
    summary = summary,
    description = description,
    errorCases = errorCases,
    examples = examples,
    successCode = successCode,
    implementation = { input: INPUT ->
        implementation(this.user() as USER, this.path1, input)
    }
)

/**
 * Builds a typed route.
 */
@JvmName("typedDirect")
@LightningServerDsl
@Deprecated("Use the new `.api` instead.")
inline fun <reified USER, reified PATH, reified PATH2, reified INPUT : Any, reified OUTPUT> HttpEndpoint.typed(
    summary: String,
    description: String = summary,
    scopes: Set<String>? = null,
    maxAge: Duration? = null,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    crossinline implementation: suspend (user: USER, path: PATH, path2: PATH2, input: INPUT) -> OUTPUT
) = typed(
    authOptions = if (USER::class == kotlin.Unit::class) com.lightningkite.lightningserver.auth.noAuth else AuthOptions<HasId<*>?>(
        buildSet {
            val type = typeOf<USER>()
            if (type.isMarkedNullable) add(null)
            add(
                AuthOption(
                    AuthType(type),
                    scopes = scopes,
                    maxAge = maxAge
                )
            )
        }
    ),
    inputType = Serialization.module.serializer(),
    outputType = Serialization.module.serializer(),
    pathType = Serialization.module.serializer(),
    path2Type = Serialization.module.serializer(),

    summary = summary,
    description = description,
    errorCases = errorCases,
    examples = examples,
    successCode = successCode,
    implementation = { user: HasId<*>?, path: PATH, path2: PATH2, input: INPUT ->
        implementation(
            user as USER,
            path,
            path2,
            input
        )
    }
)

/**
 * Builds a typed route.
 */
@JvmName("typedDirect")
@LightningServerDsl
@Deprecated("Use the new `.api` instead.")
fun <USERNN : HasId<*>, USER : USERNN?, PATH, PATH2, INPUT : Any, OUTPUT> HttpEndpoint.typed(
    authOptions: AuthOptions<USER>,
    inputType: KSerializer<INPUT>,
    outputType: KSerializer<OUTPUT>,
    pathType: KSerializer<PATH>,
    path2Type: KSerializer<PATH2>,
    summary: String,
    description: String = summary,
    errorCases: List<LSError> = listOf(),
    examples: List<ApiExample<INPUT, OUTPUT>> = listOf(),
    successCode: HttpStatus = HttpStatus.OK,
    implementation: suspend (user: USER, path: PATH, path2: PATH2, input: INPUT) -> OUTPUT
): ApiEndpoint<HasId<*>?, TypedServerPath2<PATH, PATH2>, INPUT, OUTPUT> = TypedHttpEndpoint(
    TypedServerPath2(this.path,
        TypedServerPathParameter(path.segments.filterIsInstance<ServerPath.Segment.Wildcard>()[0].name, null, pathType),
        TypedServerPathParameter(path.segments.filterIsInstance<ServerPath.Segment.Wildcard>()[1].name, null, path2Type)),
    this.method
).api<HasId<*>?, TypedServerPath2<PATH, PATH2>, INPUT, OUTPUT>(
    authOptions = authOptions,
    inputType = inputType,
    outputType = outputType,
    summary = summary,
    description = description,
    errorCases = errorCases,
    examples = examples,
    successCode = successCode,
    implementation = { input ->
        implementation(this.user() as USER, this.path1, path2, input)
    }
)