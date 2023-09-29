@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningserver.auth.token

import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.encryption.SecureHasherSettings
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.uuid
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import kotlin.time.Duration
import kotlinx.datetime.Instant
import java.util.*
import kotlin.time.Duration.Companion.seconds

class PublicTinyTokenFormatTest: TokenFormatTest() {
    override fun format(expiration: Duration): TokenFormat = PublicTinyTokenFormat(SecureHasherSettings(), expiration)

    init { TestSettings }

    @Test fun encodeData() {
        val data = CacheKeyMap(mapOf(TestSettings.TestCacheKey to RequestAuth.ExpiringValue(uuid(), Clock.System.now().plus(60.seconds))))
        println(data)
        val hex = Serialization.javaData.encodeToHexString(CacheKeyMapSerializer, data)
        println(hex)
        println(Serialization.javaData.decodeFromHexString(CacheKeyMapSerializer, hex))
    }
    @Test fun encodeData3(): Unit {
        println(Serialization.javaData.encodeToHexStringDebug(String.serializer(), "Test"))
        val hex = Serialization.javaData.encodeToHexString(String.serializer(), "Test")
        println(hex)
        println(Serialization.javaData.decodeFromHexString(String.serializer(), hex))
    }
    @Test fun encodeData2(): Unit = runBlocking {
        println(sampleAuth.await())
        println(Serialization.javaData.encodeToHexStringDebug(RequestAuthSerializable.serializer(), sampleAuth.await().serializable(Clock.System.now().plus(10.seconds))))
        val hex = Serialization.javaData.encodeToHexString(RequestAuthSerializable.serializer(), sampleAuth.await().serializable(Clock.System.now().plus(10.seconds)))
        println(hex)
        println(Serialization.javaData.decodeFromHexString(RequestAuthSerializable.serializer(), hex))
    }
}