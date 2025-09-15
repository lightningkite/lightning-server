package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.sessions.IdAndAuthMethods
import com.lightningkite.lightningserver.sessions.LogInRequest
import com.lightningkite.lightningserver.sessions.ProofsCheckResult
import com.lightningkite.lightningserver.sessions.SubSessionRequest
import com.lightningkite.lightningserver.typed.Fetcher
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

public class LiveAuthClientEndpoints<ID : Comparable<ID>>(
    public val fetcher: Fetcher,
    public val subpath: String,
    public val idSerializer: KSerializer<ID>
) : AuthClientEndpoints<ID> {
    override suspend fun logIn(input: List<Proof>): IdAndAuthMethods<ID> = fetcher(
        url = "$subpath/login",
        method = HttpMethod.POST,
        inSerializer = ListSerializer(Proof.serializer()),
        body = input,
        outSerializer = IdAndAuthMethods.serializer(idSerializer)
    )

    override suspend fun logInV2(input: LogInRequest): IdAndAuthMethods<ID> = fetcher(
        url = "$subpath/login2",
        method = HttpMethod.POST,
        inSerializer = LogInRequest.serializer(),
        body = input,
        outSerializer = IdAndAuthMethods.serializer(idSerializer)
    )

    override suspend fun checkProofs(input: List<Proof>): ProofsCheckResult<ID> = fetcher(
        url = "$subpath/proofs-check",
        method = HttpMethod.POST,
        inSerializer = ListSerializer(Proof.serializer()),
        body = input,
        outSerializer = ProofsCheckResult.serializer(idSerializer)
    )

//    override suspend fun openSession(input: String): String = fetcher(
//        url = "$subpath/open-session",
//        method = HttpMethod.POST,
//        inSerializer = String.serializer(),
//        body = input,
//        outSerializer = String.serializer()
//    )
//
//    override suspend fun getToken(input: OauthTokenRequest): OauthResponse = fetcher(
//        url = "$subpath/token",
//        method = HttpMethod.POST,
//        inSerializer = OauthTokenRequest.serializer(),
//        body = input,
//        outSerializer = OauthResponse.serializer()
//    )

    override suspend fun getTokenSimple(input: String): String = fetcher(
        url = "$subpath/token/simple",
        method = HttpMethod.POST,
        inSerializer = String.serializer(),
        body = input,
        outSerializer = String.serializer()
    )

    override suspend fun subsession(input: SubSessionRequest): String = fetcher(
        url = "$subpath/sub-session",
        method = HttpMethod.POST,
        inSerializer = SubSessionRequest.serializer(),
        body = input,
        outSerializer = String.serializer()
    )
}