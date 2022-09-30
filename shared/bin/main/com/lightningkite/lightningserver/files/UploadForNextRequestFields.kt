@file:SharedCode()
@file:UseContextualSerialization(UUID::class, ServerFile::class, Instant::class)
@file:OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)

package com.lightningkite.lightningserver.files

import com.lightningkite.khrysalis.SharedCode
import com.lightningkite.lightningdb.DatabaseModel
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.ServerFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.time.Duration
import java.time.Instant
import java.util.*
import com.lightningkite.lightningdb.*
import kotlin.reflect.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.*
import kotlinx.serialization.internal.GeneratedSerializer
import java.time.*

fun prepareUploadForNextRequestFields() {
    UploadForNextRequest::_id.setCopyImplementation { original, value -> original.copy(_id = value) }
    UploadForNextRequest::file.setCopyImplementation { original, value -> original.copy(file = value) }
    UploadForNextRequest::expires.setCopyImplementation { original, value -> original.copy(expires = value) }
}
val <K> PropChain<K, UploadForNextRequest>._id: PropChain<K, UUID> get() = this[UploadForNextRequest::_id]
val <K> PropChain<K, UploadForNextRequest>.file: PropChain<K, ServerFile> get() = this[UploadForNextRequest::file]
val <K> PropChain<K, UploadForNextRequest>.expires: PropChain<K, Instant> get() = this[UploadForNextRequest::expires]
