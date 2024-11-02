package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.anyAuth
import com.lightningkite.lightningserver.auth.anyAuthRoot
import com.lightningkite.lightningserver.auth.idString
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.typed.Documentable
import com.lightningkite.lightningserver.typed.api
import com.lightningkite.lightningserver.typed.auth

class PasskeyProofEndpoints(
    path: ServerPath,
    val challenge: ChallengeHandler,
    val interfaceInfo: Documentable.InterfaceInfo,
) : ServerPathGroup(path), Authentication.StartedProofMethod {

    init {
        path.docName = "PasskeyProof"
    }

    override val info = ProofMethodInfo(
        via = "passkey",
        property = null,
        strength = 5
    )

    init {
        Authentication.register(this)
    }

    val registerStart = path("registerStart").post.api<HasId<*>, Unit, String>(
        belongsToInterface = interfaceInfo,
        authOptions = anyAuthRoot,
        summary = "Begins a passkey credential registration",
        description = "Returns a challenge to be passed on to a client authenticator.",
        errorCases = listOf(),
        examples = listOf(),
        successCode = HttpStatus.OK,
        implementation = {
            challenge.establish(auth.subject.name, auth.idString)
        }
    )

}