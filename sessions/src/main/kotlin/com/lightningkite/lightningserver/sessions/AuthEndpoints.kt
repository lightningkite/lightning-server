package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.sessions.token.PrivateTinyTokenFormat
import com.lightningkite.lightningserver.sessions.token.TokenFormat
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId

public abstract class AuthEndpoints<SUBJECT : HasId<ID>, ID : Comparable<ID>>(
    principal: PrincipalType<SUBJECT, ID>,
    database: Runtime<Database>,
    refreshHasher: RuntimeDeferred<Signer> = secretBasis.signer("refresh"),
    private val proofHasher: RuntimeDeferred<Signer> = secretBasis.signer("proofs"),
    tokenFormat: Runtime<TokenFormat> = Runtime { PrivateTinyTokenFormat() },
) : SessionManager<SUBJECT, ID>(principal, database, refreshHasher, tokenFormat) {

}