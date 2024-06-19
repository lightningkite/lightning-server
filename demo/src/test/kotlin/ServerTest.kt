import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.demo.Server
import com.lightningkite.lightningserver.engine.UnitTestEngine
import com.lightningkite.lightningserver.engine.engine
import com.lightningkite.lightningserver.jsonschema.lightningServerSchema
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.GeneralServerSettings
import com.lightningkite.lightningserver.settings.Settings
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.typed.Documentable
import com.lightningkite.lightningserver.typed.typescriptSdk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

object TestSettings {
    init {
        Server
        Settings.populateDefaults(mapOf(
            generalSettings.name to GeneralServerSettings(
                debug = true
            )
        ))
        engine = UnitTestEngine
    }
}

class ServerTest {
    @Test fun test() {
        TestSettings
        println(Json(Serialization.jsonWithoutDefaults){ prettyPrint = true }.encodeToString(lightningServerSchema))
    }

    @Test fun generateSdk(): Unit = runBlocking {
        TestSettings
        Tasks.onSettingsReady()
        Documentable.typescriptSdk(System.out)
    }
}