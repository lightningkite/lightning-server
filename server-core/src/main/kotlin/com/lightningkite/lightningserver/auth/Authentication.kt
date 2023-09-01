@file:UseContextualSerialization(Instant::class)

package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.auth.proof.Proof
import com.lightningkite.lightningserver.auth.proof.ProofEvidence
import com.lightningkite.lightningserver.auth.proof.ProofOption
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.Request
import com.lightningkite.lightningserver.typed.ApiEndpoint
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

    data class AuthenticateResult<SUBJECT, ID>(
        val id: ID?,
        val subjectCopy: SUBJECT?,
        val options: List<ProofOption>,
        val strengthRequired: Int = 1
    )

    interface SubjectHandler<SUBJECT, ID> {
        val name: String
        val idProofs: Set<String>
        val authType: AuthType
        val applicableProofs: Set<String>
        suspend fun authenticate(vararg proofs: Proof): AuthenticateResult<SUBJECT, ID>?
        suspend fun fetch(id: ID): SUBJECT
        fun id(subject: SUBJECT): ID
        val idSerializer: KSerializer<ID>
        val subjectSerializer: KSerializer<SUBJECT>
        suspend fun <OTHER, OTHERID> permitMasquerade(
            other: SubjectHandler<OTHER, OTHERID>,
            id: ID,
            otherId: OTHERID
        ): Boolean = false

        suspend fun cache(id: ID, subject: SUBJECT?): Map<String, String> = mapOf()
    }

    interface Method {
        val humanName: String
        val validates: String
        val strength: Int
    }

    interface DirectProveMethod : Method {
        val prove: ApiEndpoint<Unit, ProofEvidence, Proof>
    }

    interface StartAndProveMethod : Method {
        val start: ApiEndpoint<Unit, String, String>
        val prove: ApiEndpoint<Unit, ProofEvidence, Proof>
    }

    interface ExternalMethod : Method {
        val start: ApiEndpoint<Unit, String, String>
        val indirectLink: ServerPath
    }

}
