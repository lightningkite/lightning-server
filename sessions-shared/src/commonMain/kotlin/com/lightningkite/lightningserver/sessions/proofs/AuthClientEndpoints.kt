package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.sessions.IdAndAuthMethods
import com.lightningkite.lightningserver.sessions.LogInRequest
import com.lightningkite.lightningserver.sessions.ProofsCheckResult
import com.lightningkite.lightningserver.sessions.SubSessionRequest
import com.lightningkite.lightningserver.sessions.proofs.oauth.OauthResponse
import com.lightningkite.lightningserver.sessions.proofs.oauth.OauthTokenRequest
import com.lightningkite.lightningserver.typed.LiveVersion

@LiveVersion(LiveAuthClientEndpoints::class)
public interface AuthClientEndpoints<ID : Comparable<ID>> {
    public suspend fun logIn(input: List<Proof>): IdAndAuthMethods<ID>
    public suspend fun logInV2(input: LogInRequest): IdAndAuthMethods<ID>
    public suspend fun checkProofs(input: List<Proof>): ProofsCheckResult<ID>

//    public suspend fun openSession(input: String): String TODO: OAuth
//    public suspend fun getToken(input: OauthTokenRequest): OauthResponse

    // requires auth
    public suspend fun getTokenSimple(input: String): String
    public suspend fun subsession(input: SubSessionRequest): String
}