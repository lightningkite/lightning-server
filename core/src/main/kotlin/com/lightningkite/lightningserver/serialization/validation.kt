package com.lightningkite.lightningserver.serialization

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.runtime.Engine
import com.lightningkite.lightningserver.runtime.engine
import com.lightningkite.services.database.validation.AnnotationValidators
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

public val AnnotationValidators.Companion.StandardWithInternalModule: Runtime<AnnotationValidators>
    get() = Runtime { AnnotationValidators(engine.externalSerialization.serializersModule) }

public val AnnotationValidators.Companion.StandardWithExternalModule: Runtime<AnnotationValidators>
    get() = Runtime { AnnotationValidators(engine.internalSerialization.serializersModule) }

public val Engine.validators: AnnotationValidators get() = server.annotationValidators()

public suspend fun <T> AnnotationValidators.assertValidOrBadRequest(serializer: KSerializer<T>, value: T) {
    val issues = validate(serializer, value)
    if (issues.isNotEmpty()) {
        throw BadRequestException(
            detail = "validation-failed",
            message = issues.entries.joinToString { "${it.key}: ${it.value}" },
            data = Json.encodeToString(issues)
        )
    }
}