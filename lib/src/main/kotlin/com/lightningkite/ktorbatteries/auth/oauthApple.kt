package com.lightningkite.ktorbatteries.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.jce.interfaces.ECKey
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import java.security.spec.*
import java.util.*
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemReader
import java.io.*
import java.security.*
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey


@KtorDsl
fun Route.oauthApple(
    defaultLanding: String = GeneralServerSettings.instance.publicUrl,
    emailToId: suspend (String) -> String
): Route {
    Security.addProvider(BouncyCastleProvider())
    val settings = AuthSettings.instance.oauth["apple"] ?: return this
    val teamId = settings.secret.substringBefore("|")
    val keyString = settings.secret.substringAfter("|")
    val algorithm = run {
        val pk = JcaPEMKeyConverter().getPrivateKey(PEMParser(StringReader("""
            -----BEGIN PRIVATE KEY-----
            ${keyString.replace(" ", "")}
            -----END PRIVATE KEY-----
        """.trimIndent())).use { it.readObject() as PrivateKeyInfo })
        Algorithm.ECDSA256(null, pk as ECPrivateKey)
    }
    return oauth(
        niceName = "Apple",
        codeName = "apple",
        authUrl = "https://appleid.apple.com/auth/authorize",
        getTokenUrl = "https://appleid.apple.com/auth/token",
        scope = "email",
        additionalParams="&response_mode=form_post",
        secretTransform = {
            JWT.create()
                .withIssuer(teamId)
                .withIssuedAt(Date())
                .withExpiresAt(Date(System.currentTimeMillis() + 1000L * 60L * 60L * 24L))
                .withAudience("https://appleid.apple.com")
                .withSubject(settings.id)
                .sign(algorithm)
        },
        defaultLanding = defaultLanding
    ) {
        val id = (it.id_token ?: throw BadRequestException("No id_token found in response"))
        val decoded = JWT.decode(id)
        if(!decoded.getClaim("email_verified").asBoolean()) throw BadRequestException("Apple has not verified the email address.")
        emailToId(decoded.getClaim("email").asString())
    }
}
