@file:UseContextualSerialization(Instant::class)

package com.lightningkite.lightningserver.auth

import com.lightningkite.prepareModelsServerCore
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.SetOnce
import com.lightningkite.lightningserver.auth.proof.*
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.Request
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.decodeUnwrappingString
import com.lightningkite.lightningserver.typed.ApiEndpoint
import com.lightningkite.lightningserver.typed.TypedServerPath0
import kotlinx.serialization.KSerializer
import kotlinx.serialization.UseContextualSerialization
import kotlinx.datetime.Instant
import kotlinx.serialization.encoding.CompositeDecoder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

object Authentication {

    init {
        prepareModelsServerCore()
    }

    interface Reader {
        suspend fun request(request: Request): RequestAuth<*>?
    }
    val readers: MutableList<Reader> = mutableListOf()

    data class AuthenticateResult<SUBJECT: HasId<ID>, ID: Comparable<ID>>(
        val id: ID?,
        val subjectCopy: SUBJECT?,
        val options: List<ProofOption>,
        val strengthRequired: Int = 1
    )

    interface SubjectHandler<SUBJECT: HasId<ID>, ID: Comparable<ID>> {
        val name: String
        val authType: AuthType
        val knownCacheTypes: List<RequestAuth.CacheKey<SUBJECT, ID, *>> get() = listOf()

        suspend fun fetch(id: ID): SUBJECT
        suspend fun findUser(property: String, value: String): SUBJECT? {
            return if(property == "_id") fetch(Serialization.json.decodeUnwrappingString(idSerializer, value)) else null
        }
        suspend fun permitMasquerade(
            other: SubjectHandler<*, *>,
            request: RequestAuth<SUBJECT>,
            otherId: Comparable<*>,
        ): Boolean = false

        suspend fun desiredStrengthFor(result: SUBJECT): Int = 5
        fun get(property: String): Boolean = subjectSerializer.descriptor.getElementIndex(property) != CompositeDecoder.UNKNOWN_NAME
        fun get(subject: SUBJECT, property: String): String? {
            return Serialization.properties.encodeToStringMap(subjectSerializer, subject)[property]
        }

        fun getSessionExpiration(subject: SUBJECT): Instant? = null

        val proofMethods: Set<ProofMethod> get() = Authentication.proofMethods.values.filter {
            it.info.property == null || get(it.info.property!!)
        }.toSet()

        val idSerializer: KSerializer<ID>
        val subjectSerializer: KSerializer<SUBJECT>

        val subjectCacheExpiration: Duration get() = 5.minutes
    }
    private val _subjects: MutableMap<AuthType, SubjectHandler<*, *>> = HashMap()
    val subjects: Map<AuthType, SubjectHandler<*, *>> get() = _subjects
    fun <SUBJECT: HasId<ID>, ID: Comparable<ID>> register(subjectHandler: SubjectHandler<SUBJECT, ID>) {
        _subjects[subjectHandler.authType] = subjectHandler
    }

    private val _proofMethods: MutableMap<String, ProofMethod> = HashMap()
    val proofMethods: Map<String, ProofMethod> get() = _proofMethods
    fun register(method: ProofMethod) {
        _proofMethods[method.info.via] = method
    }
    interface ProofMethod {
        val info: ProofMethodInfo
        suspend fun <SUBJECT: HasId<ID>, ID: Comparable<ID>> established(handler: SubjectHandler<SUBJECT, ID>, item: SUBJECT): Boolean = info.property?.let { handler.get(it) } ?: false
    }

    interface DirectProofMethod : ProofMethod {
        val prove: ApiEndpoint<HasId<*>?, TypedServerPath0, IdentificationAndPassword, Proof>
    }

    interface StartedProofMethod : ProofMethod {
        val start: ApiEndpoint<HasId<*>?, TypedServerPath0, String, String>
        val prove: ApiEndpoint<HasId<*>?, TypedServerPath0, FinishProof, Proof>
    }

    interface ExternalProofMethod : ProofMethod {
        val start: ApiEndpoint<HasId<*>?, TypedServerPath0, String, String>
        val indirectLink: ServerPath
    }

    var isAdmin: AuthOptions<*> by SetOnce { isSuperUser }
    var isDeveloper: AuthOptions<*> by SetOnce { isSuperUser }
    var isSuperUser: AuthOptions<*> by SetOnce { AuthOptions<HasId<*>>(setOf()) }
}
