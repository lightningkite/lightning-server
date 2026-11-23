package com.lightningkite.lightningserver.engine.awsserverless

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.settings.SettingsSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

class AwsAdapterLoadSettingsTest {
    object EmptyServer : ServerBuilder()

    // No LAMBDA_TASK_ROOT or S3 variables: reaching either fallback fails the test.
    class SecretOnlyAdapter : AwsAdapter(EmptyServer.build()) {
        override fun getenv(name: String): String? =
            if (name == "LIGHTNING_SERVER_SETTINGS_SECRET_ID") "the-secret" else null

        @OptIn(InternalLightningServerApi::class)
        override fun readSettingsSecret(secretId: String): String {
            secretReads.add(secretId)
            return internalSerialization.json.encodeToString(
                SettingsSerializer(settings.settings.toList(), internalSerialization.serializersModule, null),
                settings.settings.associateWith { it.default },
            )
        }

        // loadSettings runs in AwsAdapter's init, before this class's own fields exist.
        companion object {
            val secretReads = ArrayList<String>()
        }
    }

    @Test
    fun secret_settings_load_once_without_falling_back() {
        SecretOnlyAdapter()
        assertEquals(listOf("the-secret"), SecretOnlyAdapter.secretReads)
    }
}
