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

    override val healthCheckName: String get() = "Email"
    override suspend fun healthCheck(): HealthStatus {
        return try {
            emailClient.send("Test Message", listOf("test@example.com"), "Test Message", null)
            HealthStatus(HealthStatus.Level.OK)
        } catch (e: Exception) {
            e.printStackTrace()
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
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
