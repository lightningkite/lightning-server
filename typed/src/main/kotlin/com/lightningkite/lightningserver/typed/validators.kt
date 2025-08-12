package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.data.ValidationIssue
import com.lightningkite.services.data.Validators
import com.lightningkite.services.data.validate
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule

private val validatorsCache = HashMap<SerializersModule, Validators>()
public val ServerRuntime.validators: Validators get() = validatorsCache.getOrPut(internalSerializersModule) { Validators(internalSerializersModule) }

public suspend fun <T> Validators.validateOrThrow(serializer: SerializationStrategy<T>, value: T) {
    val out = ArrayList<ValidationIssue>()
    validate(serializer, value) { out.add(it) }
    if (out.isNotEmpty()) {
        throw BadRequestException(
            detail = "validation-failed",
            message = out.joinToString("; ") { "${it.path.joinToString(".")}: ${it.text}" },
            data = Json.encodeToString(out)
        )
    }
}
