package com.lightningkite.lightningserver.media

import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.MimeType
import com.lightningkite.services.database.validation.AnnotationValidators

private val mediaValidators = Runtime {
    AnnotationValidators {
        validate<MimeType, ServerFileWithMetadata> { file ->
            val acceptedTypes = types.map(::MediaType)

            when {
                file.size?.let { it > maxSize } == true -> "File is too big; max size is $maxSize bytes but file is ${file.size} bytes"

                file.mimeType?.let { type ->
                    acceptedTypes.none { it.accepts(type) }
                } == true -> "File type ${file.mimeType} does not match any of $acceptedTypes"

                else -> null
            }
        }
        validate<MimeType, ServerFileWithMetadataPreview> { preview ->
            val acceptedTypes = types.map(::MediaType)

            when {
                preview.size > maxSize -> "File is too big; max size is $maxSize bytes but file is ${preview.size} bytes"
                !preview.mimeType.accepts(preview.mimeType) -> "File type ${preview.mimeType} does not match any of $acceptedTypes"
                else -> null
            }
        }
    }
}

public val AnnotationValidators.Companion.Media: Runtime<AnnotationValidators> get() = mediaValidators