@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningserver.auth.token

import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.encryption.EncryptorSettings
import com.lightningkite.lightningserver.encryption.SecureHasherSettings
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.*

class PrivateTinyTokenFormatTest: TokenFormatTest() {
    override fun format(expiration: Duration): TokenFormat = PrivateTinyTokenFormat(EncryptorSettings(), expiration)

    init { TestSettings }
}