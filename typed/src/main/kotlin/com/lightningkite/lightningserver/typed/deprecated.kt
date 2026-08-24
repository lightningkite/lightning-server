package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.typed.ModelRestUpdatesWebSocket
import com.lightningkite.services.database.HasId

@Deprecated("Use updated spelling", ReplaceWith("ModelRestEndpointsAndUpdatesWebSocket<USER, T, ID>")) public typealias ModelRestEndpointsAndUpdatesWebsocket<USER, T, ID> = ModelRestEndpointsAndUpdatesWebSocket<USER, T, ID>
@Deprecated("Use updated spelling", ReplaceWith("ModelRestUpdatesWebSocket<USER, T, ID>")) public typealias ModelRestUpdatesWebsocket<USER, T, ID> = ModelRestUpdatesWebSocket<USER, T, ID>
