@file:UseContextualSerialization(Instant::class)

package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.SetOnce
import com.lightningkite.lightningserver.auth.proof.Proof
import com.lightningkite.lightningserver.auth.proof.ProofEvidence
import com.lightningkite.lightningserver.auth.proof.ProofMethodInfo
import com.lightningkite.lightningserver.auth.proof.ProofOption
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.Request
import com.lightningkite.lightningserver.typed.ApiEndpoint
import com.lightningkite.lightningserver.typed.TypedServerPath0
import kotlinx.serialization.KSerializer
import kotlinx.serialization.UseContextualSerialization
import kotlinx.datetime.Instant

object Authentication {

    init {
        com.lightningkite.lightningserver.auth.proof.prepareModels()
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
        val idProofs: Set<ProofMethod>
        val authType: AuthType
        val additionalProofs: Set<ProofMethod>
        val applicableProofs: Set<ProofMethod> get() = idProofs + additionalProofs
        suspend fun authenticate(vararg proofs: Proof): AuthenticateResult<SUBJECT, ID>?
        suspend fun permitMasquerade(
            other: SubjectHandler<*, *>,
            id: ID,
            otherId: Comparable<*>,
        ): Boolean = false
        val knownCacheTypes: List<RequestAuth.CacheKey<SUBJECT, ID, *>> get() = listOf()

        suspend fun fetch(id: ID): SUBJECT
        val idSerializer: KSerializer<ID>
        val subjectSerializer: KSerializer<SUBJECT>
    }
    private val _subjects: MutableMap<AuthType, SubjectHandler<*, *>> = HashMap()
    val subjects: Map<AuthType, SubjectHandler<*, *>> get() = _subjects
    fun <SUBJECT: HasId<ID>, ID: Comparable<ID>> register(subjectHandler: SubjectHandler<SUBJECT, ID>) {
        _subjects[subjectHandler.authType] = subjectHandler
    }

    interface ProofMethod {
        val name: String
        val humanName: String
        val validates: String
        val strength: Int
        val info: ProofMethodInfo get() = ProofMethodInfo(name, validates, strength)
    }

    interface EndsWithStringProofMethod : ProofMethod {
        val prove: ApiEndpoint<HasId<*>?, TypedServerPath0, ProofEvidence, Proof>
    }

    interface DirectProofMethod : EndsWithStringProofMethod {
    }

    interface StartedProofMethod : EndsWithStringProofMethod {
        val start: ApiEndpoint<HasId<*>?, TypedServerPath0, String, String>
    }

    interface ExternalProofMethod : ProofMethod {
        val start: ApiEndpoint<HasId<*>?, TypedServerPath0, String, String>
        val indirectLink: ServerPath
    }

    var isAdmin: AuthOptions<*> by SetOnce { isSuperUser }
    var isDeveloper: AuthOptions<*> by SetOnce { isSuperUser }
    var isSuperUser: AuthOptions<*> by SetOnce { AuthOptions<HasId<*>>(setOf()) }
}
