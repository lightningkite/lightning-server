package com.lightningkite.lightningserver.typed

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.ServerDefinition
import com.lightningkite.serviceabstractions.data.ValidationIssue
import com.lightningkite.serviceabstractions.data.Validators
import com.lightningkite.serviceabstractions.data.validate
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json

public val ServerDefinition.validators: Validators
    get() = get(ValidatorsKey)!!
public object ValidatorsKey: ServerDefinition.ExtensionKey<Validators>


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
