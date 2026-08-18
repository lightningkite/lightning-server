package com.lightningkite.lightningserver.media

import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.files.fileObject
import com.lightningkite.services.data.DataSize.Companion.bytes
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.MimeType
import com.lightningkite.services.database.validation.AnnotationValidators

public val AnnotationValidators.Companion.Media: Runtime<AnnotationValidators> get() = Media()

public fun AnnotationValidators.Companion.Media(runSlowChecks: Boolean = true): Runtime<AnnotationValidators> = Runtime {
    AnnotationValidators {
        if (runSlowChecks) {
            validateSuspending<MimeType, ServerFileWithMetadata> { file ->
                val acceptedTypes = types.map(::MediaType)
                val head = file.original.fileObject.head()

                when {
                    head == null -> "File does not exist"
                    head.size > maxSize -> "File is too big; max size is $maxSize bytes but file is ${head.size.bytes} bytes"
                    acceptedTypes.isNotEmpty() && acceptedTypes.none { it.accepts(head.type) }
                        -> "File type ${file.mimeType} does not match any of $acceptedTypes"
                    else -> null
                }
            }
        } else {
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
        }
        validate<MimeType, ServerFileWithMetadataPreview> { preview ->
            val acceptedTypes = types.map(::MediaType)

            when {
                preview.size > maxSize -> "File is too big; max size is $maxSize bytes but file is ${preview.size} bytes"
                acceptedTypes.isNotEmpty() && acceptedTypes.none { it.accepts(preview.mimeType) } -> "File type ${preview.mimeType} does not match any of $acceptedTypes"
                else -> null
            }
        }
    }
}