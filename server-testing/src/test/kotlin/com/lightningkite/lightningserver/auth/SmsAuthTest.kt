package com.lightningkite.lightningdb.test.com.lightningkite.lightningserver.auth

import com.lightningkite.lightningdb.collection
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningdb.test.User
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.auth.old.*
import com.lightningkite.lightningserver.auth.old.BaseAuthEndpoints
import com.lightningkite.lightningserver.auth.old.SmsAuthEndpoints
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.db.ModelSerializationInfo
import com.lightningkite.lightningserver.db.modelInfo
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.test
import com.lightningkite.lightningserver.sms.TestSMSClient
import com.lightningkite.lightningserver.typed.AuthAndPathParts
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.time.Duration
import java.util.*
import com.lightningkite.UUID
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class SmsAuthTest {
    init {
        TestSettings
    }
    @Test
    fun testPinCorrect() {
        val info = modelInfo<User, User, UUID>(
            getBaseCollection = { TestSettings.database().collection() },
            forUser = { it },
            authOptions = authOptions<User>(),
            serialization = ModelSerializationInfo()
        )
        val phoneAccess: UserPhoneAccess<User, UUID> = info.userPhoneAccess { User(email = "$it@phone", phoneNumber = it) }
        val path = ServerPath("auth")
        val baseAuth = BaseAuthEndpoints(path, phoneAccess, TestSettings.jwtSigner, expiration = 1.hours, emailExpiration = 5.minutes)
        val phoneAuth = SmsAuthEndpoints(baseAuth, phoneAccess, TestSettings.cache, TestSettings.sms)
        runBlocking {
            phoneAuth.loginSms.implementation(AuthAndPathParts(null, null, arrayOf()), "8013693729")
            val pinRegex = Regex("[0-9][0-9][0-9][0-9][0-9][0-9]")
            val pin = (TestSettings.sms() as TestSMSClient).lastMessageSent?.message?.let {
                pinRegex.find(it)?.value
            }!!
            val token = phoneAuth.loginSmsPin.implementation(AuthAndPathParts(null, null, arrayOf()), PhonePinLogin("8013693729", pin))
            assertEquals(
                HttpStatus.OK, baseAuth.getSelf.route.endpoint.test(
                headers = HttpHeaders(HttpHeader.Authorization to token, HttpHeader.ContentType to "application/json")
            ).status)
        }
    }
    @Test
    fun testPinIncorrect() {
        val info = modelInfo<User, User, UUID>(
            getBaseCollection = { TestSettings.database().collection() },
            forUser = { it },
            authOptions = authOptions<User>(),
            serialization = ModelSerializationInfo()
        )
        val phoneAccess: UserPhoneAccess<User, UUID> = info.userPhoneAccess { User(email = "$it@phone", phoneNumber = it) }
        val path = ServerPath("auth")
        val baseAuth = BaseAuthEndpoints(path, phoneAccess, TestSettings.jwtSigner, expiration = 1.hours, emailExpiration = 5.minutes)
        val phoneAuth = SmsAuthEndpoints(baseAuth, phoneAccess, TestSettings.cache, TestSettings.sms)
        runBlocking {
            phoneAuth.loginSms.implementation(AuthAndPathParts(null, null, arrayOf()), "8013693729")
            val pinRegex = Regex("[0-9][0-9][0-9][0-9][0-9][0-9]")
            val pin = "wrong"
            try {
                phoneAuth.loginSmsPin.implementation(AuthAndPathParts(null, null, arrayOf()), PhonePinLogin("8013693729", pin))
                fail()
            } catch (e: BadRequestException) {
                assertEquals("Incorrect PIN.  4 attempts remain.", e.message)
            }
        }
    }
}