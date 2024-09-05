@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningserver.auth.token

import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.lightningserver.encryption.hasher
import com.lightningkite.lightningserver.encryption.secretBasis
import kotlinx.serialization.UseContextualSerialization
import kotlin.time.Duration
import java.util.*
import com.lightningkite.UUID

class JwtTokenFormatTest: TokenFormatTest() {
    override fun format(expiration: Duration): TokenFormat = JwtTokenFormat(SecretBasis().let{{it}}.hasher("jwt"), expiration)
}