package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.serialization.assertValidOrBadRequest
import com.lightningkite.services.data.MimeType
import com.lightningkite.services.database.validation.AnnotationValidators
import kotlinx.serialization.KSerializer

@Deprecated("Renamed", ReplaceWith("assertValidOrBadRequest(serializer, value)", "com.lightningkite.lightningserver.serialization.assertValidOrBadRequest"))
public suspend fun <T> AnnotationValidators.validateOrThrow(serializer: KSerializer<T>, value: T): Unit =
    assertValidOrBadRequest(serializer, value)