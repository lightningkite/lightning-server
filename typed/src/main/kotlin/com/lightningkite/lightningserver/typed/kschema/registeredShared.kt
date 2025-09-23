package com.lightningkite.lightningserver.typed.kschema

import com.lightningkite.lightningserver.LSError
import com.lightningkite.services.database.SerializationRegistry
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.NothingSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.uuid.Uuid

@OptIn(ExperimentalSerializationApi::class)
public fun SerializationRegistry.registerLightningKiteCommonSerializers() {
    register(LSError.serializer())
}
