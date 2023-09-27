@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningserver.auth.token

import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.encryption.SecureHasherSettings
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.*

class PublicTinyTokenFormatTest: TokenFormatTest() {
    override fun format(expiration: Duration): TokenFormat = PublicTinyTokenFormat(SecureHasherSettings(), expiration)

    init { TestSettings }

    @Test fun encodeData() {
        val data = CacheKeyMap(mapOf(TestSettings.TestCacheKey to RequestAuth.ExpiringValue(UUID.randomUUID(), Instant.now().plusSeconds(60))))
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
        println(Serialization.javaData.encodeToHexStringDebug(RequestAuthSerializable.serializer(), sampleAuth.await().serializable(Instant.now().plusSeconds(10))))
        val hex = Serialization.javaData.encodeToHexString(RequestAuthSerializable.serializer(), sampleAuth.await().serializable(Instant.now().plusSeconds(10)))
        println(hex)
        println(Serialization.javaData.decodeFromHexString(RequestAuthSerializable.serializer(), hex))
    }
}