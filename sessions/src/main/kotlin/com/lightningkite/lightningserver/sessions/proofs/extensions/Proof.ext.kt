package com.lightningkite.lightningserver.sessions.proofs.extensions

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.sessions.proofs.Proof
import com.lightningkite.lightningserver.sessions.proofs.proofMethods
import kotlin.time.Duration.Companion.hours

context(_: ServerRuntime)
public val Proof.expired: Boolean get() = (expiresAt ?: (at + 1.hours)) < now()

context(server: ServerRuntime)
public suspend fun Proof.isValid(): Boolean =
    server.proofMethods.find { it.info.via == via }?.verify(this) == true