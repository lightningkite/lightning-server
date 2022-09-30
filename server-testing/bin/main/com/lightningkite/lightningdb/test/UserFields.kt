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

fun prepareUserFields() {
    User::_id.setCopyImplementation { original, value -> original.copy(_id = value) }
    User::email.setCopyImplementation { original, value -> original.copy(email = value) }
    User::age.setCopyImplementation { original, value -> original.copy(age = value) }
    User::friends.setCopyImplementation { original, value -> original.copy(friends = value) }
}
val <K> PropChain<K, User>._id: PropChain<K, UUID> get() = this[User::_id]
val <K> PropChain<K, User>.email: PropChain<K, String> get() = this[User::email]
val <K> PropChain<K, User>.age: PropChain<K, Long> get() = this[User::age]
val <K> PropChain<K, User>.friends: PropChain<K, List<UUIDFor<*>>> get() = this[User::friends]
