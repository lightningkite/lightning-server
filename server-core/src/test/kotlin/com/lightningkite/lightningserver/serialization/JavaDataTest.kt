@file:UseContextualSerialization(UUID::class)

package com.lightningkite.lightningserver.serialization

import com.lightningkite.UUID
import com.lightningkite.lightningserver.Access
import com.lightningkite.lightningserver.CompletePermissions
import com.lightningkite.lightningserver.FinalPermissions
import com.lightningkite.lightningserver.FinalServicePermissions
import com.lightningkite.lightningserver.auth.AuthType
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.RequestAuthSerializable
import com.lightningkite.lightningserver.auth.proof.Proof
import com.lightningkite.lightningserver.auth.token.PrivateTinyTokenFormat
import com.lightningkite.lightningserver.encryption.Encryptor
import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.lightningserver.encryption.encryptor
import com.lightningkite.lightningserver.testmodels.TestUser
import com.lightningkite.uuid
import kotlinx.serialization.*
import org.junit.Test
import kotlin.test.assertEquals

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
        ) {
            companion object
        }
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
        val hex = Serialization.javaData.encodeToHexString(example)
        println(hex)
        println(Serialization.javaData.decodeFromHexString<HubReport>(hex))
    }


    @Test
    fun permissionsFromHcp() {
        val sample = CompletePermissions(
            organizations = setOf(
                FinalPermissions(
                    directOwner = uuid("55555555-5555-5555-5555-555555555555"),
                    owners = setOf(uuid("55555555-5555-5555-5555-555555555555")),
                    member = uuid("55555555-5555-5555-5555-555555555555"),
                    manageBalance = true,
                    minimalMemberRead = true,
                    notifications = true,
                    sds = true,
                    subscriptions = true,
                    associates = Access.Edit,
                    applicants = Access.Edit,
                    billing = Access.Edit,
                    content = Access.Edit,
                    documents = Access.Edit,
                    exclusionMatches = Access.Edit,
                    organizations = Access.Edit,
                    policies = Access.Edit,
                    policyAnswers = Access.Edit,
                    roles = Access.Edit,
                    tags = Access.Edit,
                    tasks = Access.Edit,
                    taskSchedules = Access.Edit,
                    memberDocuments = Access.Edit,
                    members = Access.Edit,
                )
            ),
            services = FinalServicePermissions()
        )
        println(Serialization.javaData.encodeToHexStringDebug(CompletePermissions.serializer(), sample))
        assertEquals(sample, Serialization.javaData.decodeFromByteArray(
            CompletePermissions.serializer(),
            Serialization.javaData.encodeToByteArray(CompletePermissions.serializer(), sample)
        ))
    }

    @Test
    fun permissionsFromHcp2() {
        val sample2 = Serialization.json.decodeFromString(
            CompletePermissions.serializer(), """
            {
            	"organizations": [
            		{
            			"directOwner": "85ee13e4-71ac-4474-bc21-1bcd392f889a",
            			"owners": [
            				"85ee13e4-71ac-4474-bc21-1bcd392f889a"
            			],
            			"member": "3a8a8f0e-845d-4783-b5bb-d56c68fa8f2d",
            			"manageBalance": false,
            			"minimalMemberRead": false,
            			"notifications": false,
            			"sds": false,
            			"subscriptions": false,
            			"associates": "None",
            			"applicants": "None",
            			"billing": "None",
            			"content": "None",
            			"documents": "None",
            			"exclusionMatches": "None",
            			"organizations": "None",
            			"policies": "None",
            			"policyAnswers": "None",
            			"roles": "None",
            			"tags": "None",
            			"tasks": "None",
            			"taskSchedules": "None",
            			"memberDocuments": "None",
            			"members": "None"
            		}
            	],
            	"services": {
            		"forms": "None",
            		"content": "None",
            		"policies": "None",
            		"policyQuestions": "None",
            		"products": "None",
            		"roles": "None",
            		"sds": "None",
            		"tags": "None"
            	}
            }
        """.trimIndent()
        )
        println(Serialization.javaData.encodeToHexStringDebug(CompletePermissions.serializer(), sample2))
        assertEquals(sample2, Serialization.javaData.decodeFromByteArray(
            CompletePermissions.serializer(),
            Serialization.javaData.encodeToByteArray(CompletePermissions.serializer(), sample2)
        ))
    }
}