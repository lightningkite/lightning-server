package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.MimeType
import com.lightningkite.services.database.validation.AnnotationValidators
import com.lightningkite.services.files.ServerFile

private val fileValidators = Runtime {
    AnnotationValidators {
        validateSuspending<MimeType, ServerFile> { file ->
            val head = file.fileObject.head()
            when {
                head == null -> "File does not exist"
                head.size > maxSize -> "File is too big; max size is $maxSize bytes but file is ${head.size} bytes"
                types.isNotEmpty() && types.none { MediaType(it).accepts(head.type) } -> "File type ${head.type} does not match any of $types"
                else -> null
            }
        }
    }
}

public val AnnotationValidators.Companion.Files: Runtime<AnnotationValidators> get() = fileValidators