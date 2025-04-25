package com.lightningkite.lightningserver.db

import kotlin.test.Test

class StringTest {
    @Test
    fun comma() {
        val f = """
        @Serializable
        data class FunnelStart(
            val funnel: String,
            val userAgent: String,
            val version: String,
            val expireAfterMinutes: Int = 20,
            val expectedErrorRate: Float = 0.05f
        )
        @Serializable
        @GenerateDataClassPaths
        data class FunnelSummary(
            override val _id: UUID = UUID.random(),
            val funnel: String,
            val date: LocalDate,
            val status: HealthStatus.Level,
            val success: Float = 0f,
            val successAfterError: Float = 0f,
            val error: Float = 0f,
            val abandoned: Float = 0f,
            val count: Int = 0,
        ): HasId<UUID>
        
        @Serializable
        @GenerateDataClassPaths
        data class FunnelInstance(
            override val _id: UUID = UUID.random(),
            val funnel: String,
            val userAgent: String,
            val user: String? = null,
            val version: String,
            val errors: Set<String> = setOf(),
            val step: Int = 0,
            val success: Instant? = null,
            val started: Instant = now(),
            val expiry: Instant = now() + 20.minutes,
            val expectedErrorRate: Float = 0.05f
        ): HasId<UUID>
        """
        listOf(
            "FunnelStart" to listOf(
                "funnel",
                "userAgent",
                "version",
                "expireAfterMinutes",
                "expectedErrorRate",
            ),
            "FunnelSummary" to listOf(
                "_id",
                "funnel",
                "date",
                "status",
                "success",
                "successAfterError",
                "error",
                "abandoned",
                "count",
            ),
            "FunnelInstance" to listOf(
                "_id",
                "funnel",
                "userAgent",
                "user",
                "version",
                "errors",
                "step",
                "success",
                "started",
                "expiry",
                "expectedErrorRate",
            ),
        ).forEach { (name, fields) ->
            println("--$name--")
            for(it in fields) {
                f.trimIndent().fileDefaultTextForValr(name, it)?.let { d ->
                    println("Found default value $d for $it")
                } ?: println("No default value for $it")
            }
        }
    }
}