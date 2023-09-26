@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningserver.auth.token

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.UUIDSerializer
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.auth.AuthType
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.RequestAuth
import com.lightningkite.lightningserver.auth.proof.Proof
import com.lightningkite.lightningserver.encryption.SecureHasherSettings
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

class TinyTokenFormatTest: TokenFormatTest() {
    override fun format(): TokenFormat = TinyTokenFormat(SecureHasherSettings())
}