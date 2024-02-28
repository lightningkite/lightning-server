package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.DataClassPath
import com.lightningkite.lightningdb.DataClassPathSelf
import com.lightningkite.lightningdb.Modification
import com.lightningkite.rock.delay
import com.lightningkite.rock.reactive.*

private object NotInUse

fun <O, T> WritableModel<O>.prop(
    debounceTime: Long = 1000,
    property: (DataClassPathSelf<O>) -> DataClassPath<O, T>
): Writable<T> {
    val property = property(DataClassPathSelf(serializer))
    val override = Property<Any?>(NotInUse)
    var lastWriteRequest: Int = 0
    return shared {
        val original = this@prop.awaitNotNull().let { property.get(it) as T }
        val o = override.await()
        if (o != NotInUse) o as T
        else original
    }.withWrite { newValue ->
        override.value = newValue
        val me = ++lastWriteRequest
        delay(debounceTime)
        if (lastWriteRequest == me) {
            modify(property.mapModification(Modification.Assign(newValue)))
            override.value = NotInUse
        }
    }
}