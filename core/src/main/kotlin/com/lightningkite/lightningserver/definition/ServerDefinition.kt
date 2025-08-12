package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpecMap
import com.lightningkite.lightningserver.ScheduledTask
import com.lightningkite.lightningserver.Task
import com.lightningkite.lightningserver.websockets.WebSocketTopic
import kotlinx.serialization.modules.SerializersModule

public data class ServerDefinition(
    public val internalSerializersModule: SerializersModule,
    public val externalSerializersModule: SerializersModule,
    public val endpoints: PathSpecMap<ServerPathEndpoints>,
    public val schedules: Map<PathSpec0, ScheduledTask>,
    public val tasks: Map<PathSpec0, Task<*>>,
    public val webSocketTopics: PathSpecMap<WebSocketTopic<*, *>>,
    public val settings: List<ServerSetting<*, *>>,
    public override val extensions: Extensions,
): Extended


