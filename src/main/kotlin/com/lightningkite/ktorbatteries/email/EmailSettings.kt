package com.lightningkite.ktorbatteries.email

import com.lightningkite.ktorbatteries.SettingSingleton
import com.lightningkite.ktorbatteries.serverhealth.HealthCheckable
import com.lightningkite.ktorbatteries.serverhealth.HealthStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.util.*
import javax.net.ssl.SSLSocketFactory


@Serializable
data class EmailSettings(
    val option: EmailClientOption = EmailClientOption.Console,
    val smtp: SmtpConfig? = null
) : HealthCheckable {
    @Transient
    val emailClient: EmailClient = when (option) {
        EmailClientOption.Console -> ConsoleEmailClient
        EmailClientOption.Smtp -> SmtpEmailClient(
            smtp
                ?: throw IllegalArgumentException("Option SMTP was requested, but no additional information was present under the 'smtp' key.")
        )
    }

    @Transient
    var sendEmailDuringTests: Boolean = false

    companion object : SettingSingleton<EmailSettings>()

    init {
        instance = this
    }

    override suspend fun healthCheck(): HealthStatus = try {
        emailClient.send("Test Message", listOf("test@example.com"), "Test Message", null)
        HealthStatus("Email", true)
    } catch (e: Exception) {
        e.printStackTrace()
        println(e.message)
        HealthStatus("Email", false, e.message)
    }


    fun healthCheck2(): HealthStatus {
        when (option) {
            EmailClientOption.Console -> return HealthStatus("Email", true)
            EmailClientOption.Smtp -> {
                if (smtp == null) return HealthStatus("Email", false)
                else {
                    return try {
                        SSLSocketFactory.getDefault().createSocket(smtp.hostName, smtp.port)
                    } catch (e: Exception) {
                        return HealthStatus("Email", false, "Host or port failed")
                    }.use socket@{ socket ->
                        BufferedReader(InputStreamReader(socket.getInputStream())).use reader@{ reader ->
                            DataOutputStream(socket.getOutputStream()).use { output ->

                                var message = "EHLO ${smtp.hostName}\r\n"
                                output.writeBytes(message)
                                var response: String = reader.readLine()
                                if (response.substringBefore(" ") != "250") return@use HealthStatus(
                                    "Email",
                                    false,
                                    "Host or port failed"
                                )
                                while (response != "") {
                                    response = reader.readLine()
                                }

                                message = "AUTH LOGIN\r\n"
                                output.writeBytes(message)
                                response = reader.readLine()
                                if (response.substringBefore(" ") != "334") return@use HealthStatus(
                                    "Email",
                                    false,
                                    "Server hated our auth request"
                                )
                                while (response != "") {
                                    response = reader.readLine()
                                }

                                message = "${Base64.getEncoder().encodeToString(smtp.username?.toByteArray())}\r\n"
                                output.writeBytes(message)
                                response = reader.readLine()
                                if (response.substringBefore(" ") != "334") return@use HealthStatus(
                                    "Email",
                                    false,
                                    "Bad Username"
                                )
                                while (response != "") {
                                    response = reader.readLine()
                                }

                                message = "${Base64.getEncoder().encodeToString(smtp.password?.toByteArray())}\r\n"
                                output.writeBytes(message)
                                response = reader.readLine()
                                if (response.substringBefore(" ") != "334") return@use HealthStatus(
                                    "Email",
                                    false,
                                    "Bad Password"
                                )
                                while (response != "") {
                                    response = reader.readLine()
                                }

                                message = "QUIT\r\n"
                                output.writeBytes(message)

                                return@use HealthStatus("Email", true)
                            }
                        }
                    }
                }
            }
        }
    }
}

val email: EmailClient get() = EmailSettings.instance.emailClient

@Serializable
enum class EmailClientOption {
    Console,
    Smtp,
}

@Serializable
data class SmtpConfig(
    val hostName: String = "",
    val port: Int = 25,
    val username: String? = null,
    val password: String? = null,
    val useSSL: Boolean = true,
    val fromEmail: String = "",
)
