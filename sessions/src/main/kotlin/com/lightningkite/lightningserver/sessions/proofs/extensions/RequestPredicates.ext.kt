package com.lightningkite.lightningserver.sessions.proofs.extensions

import com.lightningkite.lightningserver.auth.RequestPredicates
import com.lightningkite.lightningserver.sessions.proofs.ProofMethod
import com.lightningkite.lightningserver.sessions.proofs.proofMethodAuth

public fun RequestPredicates.Builder.proofMethod(method: ProofMethod) {
    scopesPredicates.addAll(method.proofMethodAuth.scopes)
}