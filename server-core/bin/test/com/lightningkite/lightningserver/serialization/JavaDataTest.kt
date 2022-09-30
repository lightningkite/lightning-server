package com.lightningkite.lightningserver.serialization

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.decodeFromHexString
import kotlinx.serialization.encodeToHexString
import org.junit.Test

class JavaDataTest {
    @Serializable
    data class HubReport(
        val id: String,
        val secret1: Long,
        val secret2: Long,
        val battery: Float,
        val voltage: Float,
        val latitude: Float,
        val longitude: Float,
        val firmware: Int,
        val seen: List<Tag>
    ) {
        companion object
        @Serializable
        data class Tag(
            val rssi: Byte,
            val major: Short,
            val minor: Short,
        ) { companion object }
    }

    @Test
    fun test() {
        val example = HubReport(
            id = "127981723819123",
            secret1 = 2L,
            secret2 = 3L,
            battery = 1f,
            voltage = 2f,
            latitude = 2f,
            longitude = 2f,
            firmware = 1,
            seen = listOf(
                HubReport.Tag(rssi = -110, major = 1, minor = 1),
                HubReport.Tag(rssi = -110, major = 1, minor = 2),
                HubReport.Tag(rssi = -110, major = 1, minor = 3),
            )
        )
        //000f 31 32 37 39 38 31 37 32 33 38 31 39 31 32 33 0000000000000002 0000000000000003 3f800000 40000000 40000000 40000000 00000001 00000003 >9200010001 >9200010002 >9200010003
        val hex = Serialization.javaData.encodeToHexString(example)
        println(hex)
        println(Serialization.javaData.decodeFromHexString<HubReport>(hex))
    }
}