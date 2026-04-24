package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.files.*
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Helpers for working with ServerFile and FileObject values.
 *
 * Gotchas:
 * - fileObject conversion relies on a contextual ExternalServerFileSerializer being registered in the
 *   current ServerRuntime.externalSerialization serializers module. If it's missing or a different
 *   serializer is registered, a ClassCastException/IllegalStateException will occur.
 */
@OptIn(ExperimentalSerializationApi::class)
context(runtime: ServerRuntime)
public val ServerFile.fileObject: FileObject
    get() {
        val ext =
            runtime.externalSerialization.serializersModule.getContextual(ServerFile::class) as ExternalServerFileSerializer
        // TODO: If multiple file systems are present, prefer a deterministic selection strategy instead of first match.
        return ext.fileSystems.firstNotNullOfOrNull { it.parseInternalUrl(location) }
            ?: throw IllegalStateException("No file systems available to parse $location")
    }

/**
 * The file name without the last extension.
 * For names without a '.', the entire name is returned.
 */
public val FileObject.nameWithoutExtension: String get() = name.substringBeforeLast('.')

/*
TODO(API):
- Consider exposing a safe API to obtain FileObject from ServerFile without requiring callers to manage serializers.
- Provide an explicit error type for unknown file systems instead of IllegalStateException.
*/
