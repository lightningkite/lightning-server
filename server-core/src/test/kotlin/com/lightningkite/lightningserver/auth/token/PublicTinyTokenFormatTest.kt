@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningserver.auth.token

import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.lightningserver.encryption.hasher
import com.lightningkite.lightningserver.encryption.secretBasis
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.uuid
import kotlinx.coroutines.runBlocking
import com.lightningkite.now
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import kotlin.time.Duration
import java.util.*
import com.lightningkite.UUID
import kotlin.time.Duration.Companion.seconds

class PublicTinyTokenFormatTest: TokenFormatTest() {
    override fun format(expiration: Duration): TokenFormat = PublicTinyTokenFormat(SecretBasis().let{{it}}.hasher("tinytoken"), expiration)

    init { TestSettings }

    @Test fun encodeData() {
        val data = CacheKeyMap(mapOf(TestSettings.TestCacheKey to RequestAuth.ExpiringValue(uuid(), now().plus(60.seconds))))
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
        println(Serialization.javaData.encodeToHexStringDebug(RequestAuthSerializable.serializer(), sampleAuth.await().serializable(now().plus(10.seconds))))
        val hex = Serialization.javaData.encodeToHexString(RequestAuthSerializable.serializer(), sampleAuth.await().serializable(now().plus(10.seconds)))
        println(hex)
        println(Serialization.javaData.decodeFromHexString(RequestAuthSerializable.serializer(), hex))
    }
}