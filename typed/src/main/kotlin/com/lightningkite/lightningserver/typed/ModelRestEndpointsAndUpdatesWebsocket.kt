package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.typed.sdk.SdkModule
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.defaultInfo
import com.lightningkite.lightningserver.typed.sdk.clientInterface
import com.lightningkite.lightningserver.typed.sdk.info
import com.lightningkite.lightningserver.typed.sdk.pascalCase
import com.lightningkite.lightningserver.typed.sdk.sdkSettings
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.SerializableProperty

public class ModelRestEndpointsAndUpdatesWebsocket<USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    endpoints: ModelRestEndpoints<USER, T, ID>,
    websocket: ModelRestUpdatesWebsocket<USER, T, ID>
) : ServerBuilder() {
    init {
        sdkSettings.clientInterface = ClientModelRestEndpointsAndUpdatesWebsocket::class.info(endpoints.info.serializer, endpoints.info.idSerializer)

        sdkSettings.defaultInfo = SdkModule.Info(
            interfaceName = endpoints.info.collectionName.pascalCase() + "RestEndpointsAndUpdatesWebsocket",
            valueName = "rest"
        )
    }

    public val endpoints: ModelRestEndpoints<USER, T, ID> = path include endpoints
    public val websocket: ModelRestUpdatesWebsocket<USER, T, ID> = path include websocket

    public constructor(info: ModelInfo<USER, T, ID>, key: SerializableProperty<T, *>? = null) : this(
        ModelRestEndpoints(info),
        ModelRestUpdatesWebsocket(info, key)
    )

    public companion object {
        public operator fun <USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> ModelRestEndpoints<USER, T, ID>.plus(
            websocket: ModelRestUpdatesWebsocket<USER, T, ID>
        ): ModelRestEndpointsAndUpdatesWebsocket<USER, T, ID> = ModelRestEndpointsAndUpdatesWebsocket(this, websocket)
    }
}