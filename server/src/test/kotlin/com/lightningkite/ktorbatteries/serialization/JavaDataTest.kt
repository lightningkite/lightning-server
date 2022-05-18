package com.lightningkite.ktorbatteries.serialization

import kotlinx.serialization.decodeFromHexString
import kotlinx.serialization.encodeToHexString
import org.junit.Test

class JavaDataTest {
    @kotlinx.serialization.Serializable
    data class Tag(
        val rssi: Byte,
        val major: Short,
        val minor: Short,
    )
    @kotlinx.serialization.Serializable
    data class HubInfo(
        val id: Long,
        val secret1: Long,
        val secret2: Long,
        val battery: Float,
        val voltage: Float,
        val seen: List<Tag>
    )

    @Test
    fun test() {
        val example = HubInfo(
            id = 1L,
            secret1 = 2L,
            secret2 = 3L,
            battery = 1f,
            voltage = 2f,
            seen = listOf(
                Tag(rssi = -110, major = 1, minor = 1),
                Tag(rssi = -110, major = 1, minor = 2),
                Tag(rssi = -110, major = 1, minor = 3),
            )
        )
        //0000000000000001 0000000000000002 0000000000000003 3f800000 40000000  00000003  92 0001 0001  92 0001 0002  92 0001 0003
        val hex = Serialization.javaData.encodeToHexString(example)
        println(hex)
        println(Serialization.javaData.decodeFromHexString<HubInfo>(hex))
    }
}