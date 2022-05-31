@file:UseContextualSerialization(UUID::class)
package com.lightningkite.ktorbatteries.auth

import com.lightningkite.ktorbatteries.files.FilesSettings
import com.lightningkite.ktorbatteries.files.publicUrl
import com.lightningkite.ktorbatteries.files.upload
import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import com.lightningkite.ktordb.HasEmail
import com.lightningkite.ktordb.HasId
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.UseContextualSerialization
import org.apache.commons.vfs2.VFS
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals


class ConfigureAuthKtTest {
    @kotlinx.serialization.Serializable
    data class TestUser(override val _id: UUID, override val email: String): HasId<UUID>, HasEmail
    @Test fun testSelf() {
        testApplication {

        }
    }

}