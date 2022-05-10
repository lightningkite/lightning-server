package com.lightningkite.ktordb

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable


private val KSerializer_fields = HashMap<KSerializer<*>, Map<String, PartialDataClassProperty<*>>>()
@Suppress("UNCHECKED_CAST")
var <K> KSerializer<K>.fields: Map<String, PartialDataClassProperty<K>>
    get() = KSerializer_fields[this] as? Map<String, PartialDataClassProperty<K>> ?: throw IllegalStateException("Fields not assigned yet for $this")
    set(value) { KSerializer_fields[this] = value }
