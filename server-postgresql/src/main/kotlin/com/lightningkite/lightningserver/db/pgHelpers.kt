package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.KeyPath
import com.lightningkite.lightningdb.KeyPathPartial
import com.lightningkite.lightningdb.fieldSerializer
import com.lightningkite.lightningdb.nullElement
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlin.reflect.KProperty1


val KeyPathPartial<*>.colName: String get() = properties.joinToString("__") { it.name }
@Suppress("UNCHECKED_CAST")
@OptIn(InternalSerializationApi::class)
fun <K,V> KSerializer<K>.fieldSerializer(path: KeyPath<K, V>): KSerializer<V> {
    var current: KSerializer<*> = this
    path.properties.forEach {
        current = ((current.nullElement() ?: current) as KSerializer<Any?>).fieldSerializer(it as KProperty1<Any?, Any?>) as KSerializer<*>
    }
    return current as KSerializer<V>
}