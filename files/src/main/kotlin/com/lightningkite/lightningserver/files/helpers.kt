package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.files.ExternalServerFileSerializer
import com.lightningkite.services.files.FileObject
import com.lightningkite.services.files.ServerFile


context(runtime: ServerRuntime)
public val ServerFile.fileObject: FileObject get() {
    val ext = runtime.externalSerialization.serializersModule.getContextual(FileObject::class) as ExternalServerFileSerializer
    return ext.fileSystems.firstNotNullOfOrNull { it.parseExternalUrl(location) } ?: throw IllegalStateException("No file systems available to parse $location")
}