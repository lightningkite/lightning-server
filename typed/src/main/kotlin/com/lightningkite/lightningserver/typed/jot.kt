package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.ForbiddenException
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.KeyedSerializableCache
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.ConcretePath
import com.lightningkite.lightningserver.Request
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.builder.ListRegistry
import com.lightningkite.lightningserver.definition.ListRegistryExtension
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.getValue
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.data.Description
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Contextual
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.NothingSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

public interface ApiHttpHandler<PATH: PathSpec, USER: HasId<*>?, INPUT, OUTPUT>: HttpHandler<PATH> {
    public val authOptions: AuthOptions<USER>
    public val inputType: KSerializer<INPUT>
    public val outputType: KSerializer<OUTPUT>
    public val summary: String
    public val description: String
    public val successCode: HttpStatus
    public val errorCases: List<LSError>
    public val examples: List<ApiExample<INPUT, OUTPUT>>
    public val belongsToInterface: ApiClientSideInterface? get() = null
    public suspend fun ServerRuntimeWithAuth<USER, PATH>.handle(input: INPUT): OUTPUT

    override suspend fun handle(serverRuntime: ServerRuntime, request: HttpRequest<PATH>): HttpResponse = with(serverRuntime) {
        val auth = request.authChecked<USER>(authOptions)
        @Suppress("UNCHECKED_CAST") val input: INPUT = when (request.method) {
            HttpMethod.GET, HttpMethod.HEAD -> serverRuntime.externalSerialization.formDataFormat.decodeFromList(inputType, request.queryParameters)
            else -> if (inputType == Unit.serializer()) Unit as INPUT else request.body?.parse(inputType) ?: throw BadRequestException("No request body provided")
        }
        serverRuntime.validators.validateOrThrow(inputType, input)
        val runner = object: ServerRuntimeWithAuth<USER, PATH>, ServerRuntime by serverRuntime, ConcretePath<PATH> by request.pathInContext {
            override val request: Request<PATH>
                get() = request
            override val authOrNull: RequestAuth<USER & Any, *>?
                get() = auth
        }
        val result: OUTPUT = runner.handle(input)
        return HttpResponse(
            body = result.toHttpContent(request.headers.accept, outputType),
            status = successCode
        )
    }
}

private fun <USER: HasId<*>?> Request<*>.authChecked(authOptions: AuthOptions<USER>): RequestAuth<USER & Any, *>? {
    TODO()
}

public class ApiClientSideInterface(
    public val path: PathSpec0,
    public val name: String,
    public val subtypes: List<KSerializer<*>>
) {
    override fun toString(): String = "$name at $path"
}

public data class ApiExample<INPUT, OUTPUT>(
    val input: INPUT,
    val output: OUTPUT,
    val name: String = "Example",
    val notes: String? = null,
)


@Serializable
public class PrincipalTypeAndId<USER: HasId<ID>, ID: Comparable<ID>>(
    public val principalType: PrincipalType<USER, ID>,
    public val id: ID,
)

@Serializable
public class RequestAuth<USER: HasId<ID>, ID: Comparable<ID>>(
    public val principalTypeAndId: PrincipalTypeAndId<USER, ID>,
    public val sessionId: Uuid? = null,
    @Contextual public val issuedAt: Instant,
    @Description("The scopes permitted.  * indicates root access.")
    public val scopes: Set<String> = setOf("*"),
    public val thirdParty: String? = null,
    public val fromMasquerade: RequestAuth<*, *>? = null,
    public val cache: KeyedSerializableCache = KeyedSerializableCache(),
//    public val requirements: RequestRequirements? = null,  // TODO: Revive this
) {
    public val principalType: PrincipalType<USER, ID> get() = principalTypeAndId.principalType
    public val id: ID get() = principalTypeAndId.id
    override fun toString(): String = buildString {
        fromMasquerade?.let {
            append(it)
            append(" masquerading as ")
        }
        append(principalType.name)
        append(' ')
        append(id)
        sessionId?.let {
            append(" (")
            append(it)
            append(")")
        }
        thirdParty?.let {
            append(" via ")
            append(it)
        }
//        requirements?.let {
//            append(" (presigned)")
//        }
    }

    internal object Key : KeyedSerializableCache.Key<RequestAuth<*, *>?> {
        override val id: String
            get() = "auth"
        @OptIn(ExperimentalSerializationApi::class)
        @Suppress("UNCHECKED_CAST")
        override val serializer: KSerializer<RequestAuth<*, *>?> = serializer(NothingSerializer(), NothingSerializer()).nullable as KSerializer<RequestAuth<*, *>?>
        override suspend fun calculate(serverRuntime: ServerRuntime, request: Request<*>): RequestAuth<*, *>? {
            for (reader in serverRuntime.server.principalTypes) {
                @Suppress("UNCHECKED_CAST")
                reader as PrincipalType<HasId<Comparable<Any?>>, Comparable<Any?>>
                return reader.get(serverRuntime, request)?.let { it ->
                    request.headers[HttpHeader.XMasquerade]?.root?.let { m ->
                        val otherType = m.substringBefore('/')
                        val otherHandler = serverRuntime.server.principalTypes.find { it.name == otherType }
                            ?: throw BadRequestException("No subject type ${otherType} known")
                        @Suppress("UNCHECKED_CAST") val otherId = serverRuntime.externalSerialization.stringArrayFormat.decodeFromString(otherHandler.idSerializer, m.substringAfter('/')) as Comparable<Any?>
                        @Suppress("UNCHECKED_CAST")
                        if (reader.permitMasquerade(
                                otherHandler as PrincipalType<HasId<Comparable<Any?>>, Comparable<Any?>>,
                                it,
                                otherId
                            )
                        ) {
                            RequestAuth(
                                principalTypeAndId = PrincipalTypeAndId(otherHandler, otherId),
                                sessionId = it.sessionId,
                                issuedAt = it.issuedAt,
                                scopes = it.scopes,
                                thirdParty = "${it.principalType.name} ${it.id} masquerading",
                                fromMasquerade = it
                            )
                        } else {
                            throw ForbiddenException()
                        }
                    } ?: it
                }?.also {
//                    it.requirements?.assert(request)
                } ?: continue
            }
            return null
        }
    }
}

@Serializable
internal data class RequestAuthSerializable(
    val subjectType: String,
    val sessionId: Uuid?,
    val id: String,
    @Contextual val issuedAt: Instant,
    @Contextual val expiresAt: Instant,
    @Description("The scopes permitted.  * indicates root access.")
    val scopes: Set<String>,
    val cacheQuickAccess: Map<String, ByteArray> = mapOf(),
    val thirdParty: String? = null,
//    val requirements: RequestAuth.RequestRequirements? = null,
) {
    companion object {
        val dummy = RequestAuthSerializable("", null, "", Instant.DISTANT_PAST, Instant.DISTANT_PAST, setOf())
    }
}

public interface ServerRuntimeWithAuth<USER: HasId<*>?, PATH: PathSpec>: ServerRuntime, ConcretePath<PATH> {
    public val request: Request<PATH>
    public val authOrNull: RequestAuth<USER & Any, *>?
}


public val ServerBuilder.principalTypes: ListRegistry<PrincipalType<*, *>> by PrincipalType.RegistryKey
public val ServerDefinition.principalTypes: List<PrincipalType<*, *>> by PrincipalType.RegistryKey

public interface PrincipalType<SUBJECT: HasId<ID>, ID: Comparable<ID>> {
    public object RegistryKey : ListRegistryExtension<PrincipalType<*, *>>

    public val idSerializer: KSerializer<ID>
    public val name: String get() = subjectSerializer.descriptor.serialName
    public val cacheTypes: Collection<CacheKey<*, *, *>>
    public val subjectCacheExpiration: Duration get() = 5.minutes
    public suspend fun get(serverRuntime: ServerRuntime, request: Request<*>): RequestAuth<SUBJECT, ID>? = null

    public val subjectSerializer: KSerializer<SUBJECT>
    public suspend fun fetch(serverRuntime: ServerRuntime, id: ID): SUBJECT

    public suspend fun permitMasquerade(
        other: PrincipalType<*, *>,
        request: RequestAuth<SUBJECT, ID>,
        otherId: Comparable<*>,
    ): Boolean = false

    public interface CacheKey<SUBJECT: HasId<ID>, ID: Comparable<ID>, T> {
        public val id: String
        public val serializer: KSerializer<T>
        public suspend fun calculate(serverRuntime: ServerRuntime, auth: RequestAuth<SUBJECT, ID>): T
        public val expireAfter: Duration? get() = null
    }
}

public class AuthOption<SUBJECT: HasId<ID>, ID: Comparable<ID>>(
    public val type: PrincipalType<SUBJECT, ID>,
    @Description("The required scopes.  Null indicates no special scope is required.  * indicates root access.")
    public val scopes: Set<String>? = setOf("*"),
    public val maxAge: Duration? = null,
    public val limitationDescription: String? = null,
    public val additionalRequirement: suspend ServerRuntime.(Request<*>) -> Boolean = { _ -> true }
) {
    override fun toString(): String = "$type $scopes $maxAge"
}
public class AuthOptions<out USER : HasId<*>?>(public val options: Set<AuthOption<out USER & Any, *>?>)










//public interface PrincipalType2<SUBJECT: HasId<ID>, ID: Comparable<ID>> {
//    public val idSerializer: KSerializer<ID>
//    public val subjectSerializer: KSerializer<SUBJECT>
//    public val name: String get() = subjectSerializer.descriptor.serialName
//
//    public val cacheTypes: Map<String, KSerializer<*>>
//
//    public suspend fun fetch(id: ID): SUBJECT
//
//    public fun containsProperty(property: String): Boolean = subjectSerializer.descriptor.getElementIndex(property) != CompositeDecoder.UNKNOWN_NAME
//    public fun get(subject: SUBJECT, property: String): String?
//    public suspend fun findUser(property: String, value: String): SUBJECT? {
//        return when (property) {
//            "$name/_id" -> fetch(Serialization.json.decodeUnwrappingString(idSerializer, value))
//            else -> null
//        }
//    }
//
//    public suspend fun desiredStrengthFor(result: SUBJECT): Int = 5
//    public suspend fun maxSessionDuration(subject: SUBJECT): Duration? = null
//    public suspend fun permitMasquerade(
//        other: PrincipalType<*, *>,
//        request: RequestAuth<SUBJECT>,
//        otherId: Comparable<*>,
//    ): Boolean = false
//
//    public val subjectCacheExpiration: Duration get() = 5.minutes
//}

