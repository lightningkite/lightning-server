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
@Deprecated("Replace with 'externalFile'", ReplaceWith("externalFile", "com.lightningkite.lightningserver.files.externalFile"))
context(runtime: ServerRuntime)
public val ServerFile.fileObject: ExternalFile get() = externalFile

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
public val ServerFile.externalFile: ExternalFile
    get() {
        val ext =
            runtime.externalSerialization.serializersModule.getContextual(ServerFile::class) as ExternalServerFileSerializer
        // Server-internal resolution: handles the canonical `sf://<name>/<path>` form a ServerFile stores, and falls
        // back to the backend-specific absolute URLs written before that form existed. Never use it on client input —
        // both forms are unsigned. The parser keys off the file system name, so multiple systems are unambiguous.
        return ExternalFile.Parser(ext.fileSystems).parseOrNull(location)
            ?: throw IllegalStateException("No file systems available to parse $location")
    }

/**
 * The file name without the last extension.
 * For names without a '.', the entire name is returned.
 */
public val ExternalFile.nameWithoutExtension: String get() = name.substringBeforeLast('.')

/*
TODO(API):
- Consider exposing a safe API to obtain FileObject from ServerFile without requiring callers to manage serializers.
- Provide an explicit error type for unknown file systems instead of IllegalStateException.
*/
