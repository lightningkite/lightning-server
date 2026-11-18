package com.lightningkite.lightningserver.guide.samples

// region settings-imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.settings.*
import kotlinx.serialization.Serializable
import kotlin.test.*
// endregion settings-imports

// region settings-server
@Serializable
data class FeatureFlags(val newCheckout: Boolean = false, val maxCartItems: Int = 50)

object StoreSettingsServer : ServerBuilder() {
    val supportEmail = setting("supportEmail", "support@example.com")
    val featureFlags = setting("featureFlags", FeatureFlags())
    val maintenanceMessage = setting<String?>("maintenanceMessage", null, optional = true)
    val uploadLimitBytes = setting("uploadLimitMegabytes", 10) { megabytes -> megabytes * 1024L * 1024L }

    val status = path.path("status").get bind HttpHandler {
        HttpResponse.plainText(maintenanceMessage() ?: "Open. Questions? ${supportEmail()}")
    }
}
// endregion settings-server

// region settings-test
fun shopSettingsTest() = StoreSettingsServer.testBlocking(settings = {
    maintenanceMessage set "Back at noon"
    uploadLimitBytes set 2
}) {
    assertEquals("Back at noon", status.test().body!!.text())
    assertEquals(2L * 1024 * 1024, uploadLimitBytes())
    assertEquals(FeatureFlags(), featureFlags())
}
// endregion settings-test
