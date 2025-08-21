package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.MetricReporter
import com.lightningkite.services.Setting
import kotlinx.serialization.KSerializer

public fun interface Runtime<GOAL> {
    context(server: ServerRuntime)
    public operator fun invoke(): GOAL
}

public interface ServerSetting<SETTING, RESULT> : Runtime<RESULT> {
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

    context(server: ServerRuntime)
    override fun invoke(): RESULT = server.settings.get(this, server)
}

private data class BasicServerSetting<SETTING, RESULT>(
    override val settingName: String,
    override val default: SETTING,
    override val serializer: KSerializer<SETTING>,
    override val optional: Boolean,
    private val getter: ServerRuntime.(SETTING) -> RESULT
) : ServerSetting<SETTING, RESULT> {
    context(server: ServerRuntime)
    override fun get(setting: SETTING): RESULT = getter(server, setting)
}

public fun <SETTING, RESULT> ServerSetting(
    name: String,
    default: SETTING,
    serializer: KSerializer<SETTING>,
    optional: Boolean = false,
    getter: ServerRuntime.(SETTING) -> RESULT
) : ServerSetting<SETTING, RESULT> =
    BasicServerSetting(name, default, serializer, optional, getter)

public fun <SETTING : Setting<RESULT>, RESULT> ServerSetting(
    name: String,
    default: SETTING,
    serializer: KSerializer<SETTING>,
    optional: Boolean = false
): ServerSetting<SETTING, RESULT> =
    ServerSetting(name, default, serializer, optional) { it.invoke(name, this) }

private data class BasicDirectServerSetting<SETTING>(
    override val settingName: String,
    override val default: SETTING,
    override val serializer: KSerializer<SETTING>,
    override val optional: Boolean,
) : ServerSetting.Direct<SETTING>

public fun <SETTING> ServerSetting(
    name: String,
    default: SETTING,
    serializer: KSerializer<SETTING>,
    optional: Boolean = false
) : ServerSetting.Direct<SETTING> =
    BasicDirectServerSetting(name, default, serializer, optional)


