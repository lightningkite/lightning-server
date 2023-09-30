@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningserver.auth.token

import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.lightningserver.encryption.encryptor
import com.lightningkite.lightningserver.encryption.secretBasis
import kotlinx.serialization.*
import kotlin.time.Duration
import java.util.*

class PrivateTinyTokenFormatTest: TokenFormatTest() {
    override fun format(expiration: Duration): TokenFormat = PrivateTinyTokenFormat(SecretBasis().let{{it}}.encryptor("tinytoken"), expiration)

    init { TestSettings }
}