package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.typed.sdk.*
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.defaultInfo
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.SerializableProperty

public class ModelRestEndpointsAndUpdatesWebSocket<USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    endpoints: ModelRestEndpoints<USER, T, ID>,
    webSocket: ModelRestUpdatesWebSocket<USER, T, ID>,
) : ServerBuilder() {
    init {
        sdkSettings.clientInterface = ClientModelRestEndpointsAndUpdatesWebSocket::class.info(
            endpoints.info.serializer,
            endpoints.info.idSerializer
        )

        sdkSettings.defaultInfo = SdkModule.Info(
            interfaceName = endpoints.info.tableName.pascalCase() + "RestEndpointsAndUpdatesWebSocket",
            valueName = "rest"
        )
    }

    public val endpoints: ModelRestEndpoints<USER, T, ID> = path include endpoints
    public val webSocket: ModelRestUpdatesWebSocket<USER, T, ID> = path include webSocket

    public constructor(info: ModelInfo<USER, T, ID>, key: SerializableProperty<T, *>? = null) : this(
        ModelRestEndpoints(info),
        ModelRestUpdatesWebSocket(info, key)
    )

    public companion object {
        public operator fun <USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> ModelRestEndpoints<USER, T, ID>.plus(
            webSocket: ModelRestUpdatesWebSocket<USER, T, ID>,
        ): ModelRestEndpointsAndUpdatesWebSocket<USER, T, ID> = ModelRestEndpointsAndUpdatesWebSocket(this, webSocket)
    }
}