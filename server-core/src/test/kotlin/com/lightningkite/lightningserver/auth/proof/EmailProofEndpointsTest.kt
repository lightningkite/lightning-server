package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.email.Email
import com.lightningkite.lightningserver.email.EmailLabeledValue
import com.lightningkite.lightningserver.email.TestEmailClient
import com.lightningkite.lightningserver.serialization.Serialization
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class EmailProofEndpointsTest {
    @Test fun test(): Unit = runBlocking {
        TestSettings.proofEmail.send("test@test.com") { proof ->
            Email(
                to = listOf(EmailLabeledValue("test@test.com")),
                subject = "Login",
                plainText = """
                    |Test got proof 
                    |${Serialization.json.encodeToString(proof).encodeURLQueryComponent()}
                    |${Serialization.json.encodeToString(proof).let{ Base64.getEncoder().encodeToString(it.toByteArray()) }}
                    |""".trimMargin()
            )
        }
        assertNotNull(TestEmailClient.lastEmailSent)
        TestEmailClient.lastEmailSent?.plainText?.let { println(it) }
    }
}