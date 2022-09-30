@file:UseContextualSerialization(Instant::class)
@file:OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)

package com.lightningkite.lightningserver.tasks

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.core.LightningServerDsl
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.serialization.serializer
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.*
import kotlinx.serialization.internal.GeneratedSerializer
import java.time.*
import java.util.*

fun prepareActionHasOccurredFields() {
    ActionHasOccurred::_id.setCopyImplementation { original, value -> original.copy(_id = value) }
    ActionHasOccurred::started.setCopyImplementation { original, value -> original.copy(started = value) }
    ActionHasOccurred::completed.setCopyImplementation { original, value -> original.copy(completed = value) }
    ActionHasOccurred::errorMessage.setCopyImplementation { original, value -> original.copy(errorMessage = value) }
}
val <K> PropChain<K, ActionHasOccurred>._id: PropChain<K, String> get() = this[ActionHasOccurred::_id]
val <K> PropChain<K, ActionHasOccurred>.started: PropChain<K, Instant?> get() = this[ActionHasOccurred::started]
val <K> PropChain<K, ActionHasOccurred>.completed: PropChain<K, Instant?> get() = this[ActionHasOccurred::completed]
val <K> PropChain<K, ActionHasOccurred>.errorMessage: PropChain<K, String?> get() = this[ActionHasOccurred::errorMessage]
