package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.MetricSink
import com.lightningkite.services.Setting
import kotlinx.serialization.KSerializer

public interface ServerSetting<SETTING, RESULT> {
    public val settingName: String
    public val serializer: KSerializer<SETTING>
    public val default: SETTING
    public val optional: Boolean get() = false

    context(server: ServerRuntime)
    public fun get(setting: SETTING): RESULT

    public interface Direct<Setting> : ServerSetting<Setting, Setting> {
        context(server: ServerRuntime)
        override fun get(setting: Setting): Setting = setting
    }
}

private data class BasicServerSetting<SETTING, RESULT>(
    override val settingName: String,
    override val serializer: KSerializer<SETTING>,
    override val default: SETTING,
    override val optional: Boolean,
    private val getter: ServerRuntime.(SETTING) -> RESULT
) : ServerSetting<SETTING, RESULT> {
    context(server: ServerRuntime)
    override fun get(setting: SETTING): RESULT = getter(server, setting)
}

public fun <SETTING, RESULT> ServerSetting(
    name: String,
    serializer: KSerializer<SETTING>,
    default: SETTING,
    optional: Boolean = false,
    getter: ServerRuntime.(SETTING) -> RESULT
) : ServerSetting<SETTING, RESULT> =
    BasicServerSetting(name, serializer, default, optional, getter)

public fun <SETTING : Setting<RESULT>, RESULT> ServerSetting(
    name: String,
    serializer: KSerializer<SETTING>,
    default: SETTING,
    optional: Boolean = false
): ServerSetting<SETTING, RESULT> =
    ServerSetting(name, serializer, default, optional) { it.invoke(name, this) }

private data class BasicDirectServerSetting<SETTING>(
    override val settingName: String,
    override val serializer: KSerializer<SETTING>,
    override val default: SETTING,
    override val optional: Boolean,
) : ServerSetting.Direct<SETTING>

public fun <SETTING> ServerSetting(
    name: String,
    serializer: KSerializer<SETTING>,
    default: SETTING,
    optional: Boolean = false
) : ServerSetting.Direct<SETTING> =
    BasicDirectServerSetting(name, serializer, default, optional)


