package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.DataClassPath
import com.lightningkite.lightningdb.DataClassPathPartial
import com.lightningkite.lightningdb.nullElement
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import com.lightningkite.lightningdb.SerializableProperty


val DataClassPathPartial<*>.colName: String get() = properties.joinToString("__") { it.name }
