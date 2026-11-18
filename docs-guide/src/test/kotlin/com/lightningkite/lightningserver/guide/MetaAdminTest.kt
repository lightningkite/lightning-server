package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.MetaServer
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.runtime.test.testBlocking
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.HealthStatus
import com.lightningkite.services.database.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetaAdminTest {
    @Test
    fun `online, health and openapi respond`() = MetaServer.testBlocking(settings = {
        database set Database.Settings()
        cache set Cache.Settings()
    }) {
        assertEquals("Server is running.", MetaServer.meta.isOnline.test().body!!.text())

        val health = MetaServer.meta.health.test(null, Unit)
        assertEquals(HealthStatus.Level.OK, health.features.getValue("database").level)
        assertEquals(HealthStatus.Level.OK, health.features.getValue("cache").level)

        val spec = MetaServer.meta.openApiJson.test()
        assertEquals(HttpStatus.OK, spec.status)
        assertTrue(spec.body!!.text().contains("\"openapi\""))
    }
}
