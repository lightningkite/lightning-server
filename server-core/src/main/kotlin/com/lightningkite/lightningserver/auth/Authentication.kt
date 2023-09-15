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
import java.time.Instant

object Authentication {

    // TODO: Link back up to old auth system
    //   TODO: Parse sessions as authentication
    //   TODO: Parse tokens as authentication
    //   TODO: Generate tokens
    // TODO: Fix up old auth system
    // TODO: Easier SubjectHandler impl
    // TODO: Implement Methods

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
        val idProofs: Set<String>
        val authType: AuthType
        val applicableProofs: Set<String>
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

    interface DirectProofMethod : ProofMethod {
        val prove: ApiEndpoint<*, TypedServerPath0, ProofEvidence, Proof>
    }

    interface StartedProofMethod : ProofMethod {
        val start: ApiEndpoint<*, TypedServerPath0, String, String>
        val prove: ApiEndpoint<*, TypedServerPath0, ProofEvidence, Proof>
    }

    interface ExternalProofMethod : ProofMethod {
        val start: ApiEndpoint<*, TypedServerPath0, String, String>
        val indirectLink: ServerPath
    }

    var isSuperUser: AuthOptions<*> by SetOnce { AuthOptions<HasId<*>>(setOf()) }
}
