package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.McpServer
import com.lightningkite.lightningserver.guide.samples.oauthProviderDiscoveryTest
import com.lightningkite.lightningserver.settings.IncompleteSettingsException
import com.lightningkite.lightningserver.settings.ServerSettings
import com.lightningkite.lightningserver.settings.loadFromFile
import com.lightningkite.services.kfile.KFile
import kotlinx.serialization.modules.EmptySerializersModule
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OauthProviderTest {
    @Test
    fun `an MCP client without a token is pointed at the metadata`() { oauthProviderDiscoveryTest() }

    @Test
    fun `a settings file without the signing key fails at startup`() {
        val file = KFile(Files.createTempDirectory("oauth-provider-settings").resolve("settings.json").toString())
        file.writeString("""{ "database": "ram", "cache": "ram" }""")
        val e = assertFailsWith<IncompleteSettingsException> {
            ServerSettings(McpServer.build()).loadFromFile(file, EmptySerializersModule())
        }
        assertTrue("oidcSigningKey" in e.missing.map { it.name })
    }
}
