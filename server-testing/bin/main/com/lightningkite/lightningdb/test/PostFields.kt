@file:UseContextualSerialization(UUID::class, Instant::class)
@file:OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)

package com.lightningkite.lightningdb.test

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.DatabaseModel
import com.lightningkite.lightningdb.UUIDFor
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.time.Instant
import java.util.*
import kotlin.reflect.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.*
import kotlinx.serialization.internal.GeneratedSerializer
import java.time.*

fun preparePostFields() {
    Post::_id.setCopyImplementation { original, value -> original.copy(_id = value) }
    Post::author.setCopyImplementation { original, value -> original.copy(author = value) }
    Post::content.setCopyImplementation { original, value -> original.copy(content = value) }
    Post::at.setCopyImplementation { original, value -> original.copy(at = value) }
}
val <K> PropChain<K, Post>._id: PropChain<K, UUID> get() = this[Post::_id]
val <K> PropChain<K, Post>.author: PropChain<K, UUIDFor<*>> get() = this[Post::author]
val <K> PropChain<K, Post>.content: PropChain<K, String> get() = this[Post::content]
val <K> PropChain<K, Post>.at: PropChain<K, Long?> get() = this[Post::at]
