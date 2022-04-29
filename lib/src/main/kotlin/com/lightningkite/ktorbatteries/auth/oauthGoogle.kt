package com.lightningkite.ktorbatteries.auth

import com.lightningkite.ktorbatteries.client
import com.lightningkite.ktorbatteries.routes.fullPath
import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.*

fun Route.oauthGoogle(defaultLanding: String, emailToId: suspend (String)->String) {
    val baseUrl = GeneralServerSettings.instance.publicUrl + this.fullPath
    get("login") {
        val token = makeToken {
            withClaim("landing", call.request.queryParameters["redirect_uri"] ?: (GeneralServerSettings.instance.publicUrl + defaultLanding))
                .withClaim("host", call.request.origin.remoteHost)
                .withExpiresAt(Date(System.currentTimeMillis() + 10L * 60L * 1000L))
        }
        call.respondRedirect("""
                    https://accounts.google.com/o/oauth2/v2/auth?
                    scope=https%3A//www.googleapis.com/auth/userinfo.email&
                    access_type=online&
                    include_granted_scopes=true&
                    response_type=code&
                    state=${token}&
                    redirect_uri=${baseUrl}/callback&
                    client_id=${AuthSettings.instance.oauth["google"]!!.id}
                """.trimIndent().replace("\n", ""))
    }
    get("callback") {
        val token = call.request.queryParameters["state"] ?: throw BadRequestException("State token missing")
        val tokenResult = checkToken(token)
        if(tokenResult == null) throw BadRequestException("Invalid state token")
        val destinationUri = tokenResult.getClaim("landing")!!.asString()
        call.request.queryParameters["error"]?.let {
            throw BadRequestException("Got error code '${it}' from Google.")
        } ?: call.request.queryParameters["code"]?.let { code ->
            val response: GoogleResponse = client.post(" https://oauth2.googleapis.com/token") {
                formData {
                    parameter("code", code)
                    parameter("client_id", AuthSettings.instance.oauth["google"]!!.id)
                    parameter("client_secret", AuthSettings.instance.oauth["google"]!!.secret)
                    parameter("redirect_uri", "http://localhost:8080/google/callback")
                    parameter("grant_type", "authorization_code")
                }
            }.body()
            val response2: GoogleResponse2 = client.get("https://www.googleapis.com/oauth2/v2/userinfo") {
                headers {
                    append("Authorization", "Bearer ${response.access_token}")
                }
            }.body()
            if(!response2.verified_email) throw BadRequestException("Google has not verified the email '${response2.email}'.")
            call.respondRedirect(destinationUri + "?jwt=${makeToken(emailToId(response2.email))}")
        }
    }
}


@Serializable
data class GoogleResponse(
    val access_token: String,
    val scope: String,
    val state: String? = null
)

@Serializable
data class GoogleResponse2(
    val verified_email: Boolean,
    val email: String,
    val picture: String
)

//http://localhost:8080/google/callback
//http://0.0.0.0:8080/google/callback