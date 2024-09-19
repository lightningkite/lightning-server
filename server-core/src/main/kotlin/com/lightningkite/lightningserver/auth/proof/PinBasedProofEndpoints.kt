package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.encryption.SecureHasher
import com.lightningkite.lightningserver.encryption.hasher
import com.lightningkite.lightningserver.encryption.secretBasis
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.typed.ApiExample
import com.lightningkite.lightningserver.typed.Documentable
import com.lightningkite.lightningserver.typed.api
import com.lightningkite.now
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

abstract class PinBasedProofEndpoints(
    path: ServerPath,
    val name: String,
    val property: String,
    val proofHasher: () -> SecureHasher = secretBasis.hasher("proof"),
    val pin: PinHandler,
    val interfaceInfo: Documentable.InterfaceInfo,
    val exampleTarget: String
) : ServerPathGroup(path), Authentication.StartedProofMethod {

    open fun normalize(to: String): String = to.lowercase().trim()
    abstract suspend fun send(to: String, pin: String)
    final override val info: ProofMethodInfo = ProofMethodInfo(
        via = name,
        property = property,
        strength = 10
    )

    override val start = path("start").post.api(
        belongsToInterface = interfaceInfo,
        authOptions = noAuth,
        summary = "Begin $name Ownership Proof",
        description = "Sends a login code to the given ${name.lowercase()}.  The message will contain both a PIN that can be combined with the returned key to log in.",
        errorCases = listOf(),
        examples = listOf(
            ApiExample(
                input = exampleTarget,
                output = "generated_opaque_identifier",
            ),
            ApiExample(
                input = exampleTarget.uppercase(),
                output = "generated_opaque_identifier",
                name = "Casing doesn't matter",
                notes = "The casing of the target is ignored, and the input is trimmed."
            ),
        ),
        successCode = HttpStatus.OK,
        implementation = { addressUnsafe: String ->
            val address = normalize(addressUnsafe)
            val p = pin.establish(address)
            send(address, p.pin)
            p.key
        }
    )
    protected fun issueProof(destination: String): Proof {
        return proofHasher().makeProof(
            info = info,
            property = info.property!!,
            value = destination,
            at = now()
        )
    }
    override val prove = path("prove").post.api(
        belongsToInterface = interfaceInfo,
        authOptions = noAuth,
        summary = "Prove ${info.property} ownership",
        description = "Logs in to the given account with a PIN that was sent earlier and the key from that request.  Note that the PIN expires in ${pin.expiration.inWholeMinutes} minutes, and you are only permitted ${pin.maxAttempts} attempts.",
        errorCases = listOf(),
        examples = listOf(
            ApiExample(
                input = FinishProof("key-from-start-call", pin.generate()),
                output = Proof(
                    via = name,
                    property = info.property!!,
                    strength = info.strength,
                    value = exampleTarget,
                    at = now(),
                    signature = "opaquesignaturevalue"
                )
            )
        ),
        successCode = HttpStatus.OK,
        implementation = { input: FinishProof ->
            proofHasher().makeProof(
                info = info,
                property = info.property!!,
                value = pin.assert(input.key, input.password),
                at = now()
            )
        }
    )

    override suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> established(
        handler: Authentication.SubjectHandler<SUBJECT, ID>,
        item: SUBJECT
    ): Boolean = handler.get(item, info.property!!) != null
}