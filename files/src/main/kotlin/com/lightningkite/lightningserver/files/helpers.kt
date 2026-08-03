package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.files.*
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Helpers for working with ServerFile and ExternalFile values.
 *
 * Gotchas:
 * - fileObject conversion relies on a contextual ExternalServerFileSerializer being registered in the
 *   current ServerRuntime.externalSerialization serializers module. If it's missing or a different
 *   serializer is registered, a ClassCastException/IllegalStateException will occur.
 */
@OptIn(ExperimentalSerializationApi::class)
context(runtime: ServerRuntime)
public val ServerFile.fileObject: ExternalFile
    get() {
        val ext =
            runtime.externalSerialization.serializersModule.getContextual(ServerFile::class) as ExternalServerFileSerializer
        // Stored references resolve exactly as the serializer resolves them: the canonical sf:// form
        // first, falling back to the backend-specific URLs written before that form existed.
        // TODO: Cache parser
        return ExternalFile.Parser(ext.fileSystems).parseOrNull(location)
            ?: throw IllegalStateException("No file systems available to parse $location")
    }

/*
TODO(API):
- Consider exposing a safe API to obtain ExternalFile from ServerFile without requiring callers to manage serializers.
- Provide an explicit error type for unknown file systems instead of IllegalStateException.
*/
