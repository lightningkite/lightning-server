@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.lightningdb

import kotlinx.serialization.*
import kotlinx.serialization.builtins.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import java.util.*
import com.lightningkite.lightningdb.SerializableProperty

private fun <T> commonOptions(inner: KSerializer<T>): List<MySealedClassSerializer.Option<Modification<T>, *>> = listOf(
    MySealedClassSerializer.Option(Modification.Chain.serializer(inner)) { it is Modification.Chain },
    MySealedClassSerializer.Option(Modification.Assign.serializer(inner)) { it is Modification.Assign },
)
private fun <T: Any> nullableOptions(inner: KSerializer<T>): List<MySealedClassSerializer.Option<Modification<T?>, *>> = commonOptions(inner.nullable) + listOf(
    MySealedClassSerializer.Option(Modification.IfNotNull.serializer(inner)) { it is Modification.IfNotNull },
)
private fun <T: Comparable<T>> comparableOptions(inner: KSerializer<T>): List<MySealedClassSerializer.Option<Modification<T>, *>> = commonOptions(inner) + listOf(
    MySealedClassSerializer.Option(Modification.CoerceAtLeast.serializer(inner)) { it is Modification.CoerceAtLeast },
    MySealedClassSerializer.Option(Modification.CoerceAtMost.serializer(inner)) { it is Modification.CoerceAtMost },
)
private fun <T> listOptions(element: KSerializer<T>): List<MySealedClassSerializer.Option<Modification<List<T>>, *>>  = commonOptions(ListSerializer(element)) + listOf(
    MySealedClassSerializer.Option(Modification.ListAppend.serializer(element), setOf("AppendList")) { it is Modification.ListAppend },
    MySealedClassSerializer.Option(Modification.ListRemove.serializer(element), setOf("Remove")) { it is Modification.ListRemove },
    MySealedClassSerializer.Option(Modification.ListRemoveInstances.serializer(element), setOf("RemoveInstances")) { it is Modification.ListRemoveInstances },
    MySealedClassSerializer.Option(Modification.ListDropFirst.serializer(element), setOf("DropFirst")) { it is Modification.ListDropFirst },
    MySealedClassSerializer.Option(Modification.ListDropLast.serializer(element), setOf("DropLast")) { it is Modification.ListDropLast },
    MySealedClassSerializer.Option(Modification.ListPerElement.serializer(element), setOf("PerElement")) { it is Modification.ListPerElement },
)
private fun <T> setOptions(element: KSerializer<T>): List<MySealedClassSerializer.Option<Modification<Set<T>>, *>>  = commonOptions(SetSerializer(element)) + listOf(
    MySealedClassSerializer.Option(Modification.SetAppend.serializer(element), setOf("AppendSet")) { it is Modification.SetAppend },
    MySealedClassSerializer.Option(Modification.SetRemove.serializer(element)) { it is Modification.SetRemove },
    MySealedClassSerializer.Option(Modification.SetRemoveInstances.serializer(element)) { it is Modification.SetRemoveInstances },
    MySealedClassSerializer.Option(Modification.SetDropFirst.serializer(element)) { it is Modification.SetDropFirst },
    MySealedClassSerializer.Option(Modification.SetDropLast.serializer(element)) { it is Modification.SetDropLast },
    MySealedClassSerializer.Option(Modification.SetPerElement.serializer(element)) { it is Modification.SetPerElement },
)
private fun <T> stringMapOptions(element: KSerializer<T>): List<MySealedClassSerializer.Option<Modification<Map<String, T>>, *>>  = commonOptions(
    MapSerializer(String.serializer(), element)
) + listOf(
    MySealedClassSerializer.Option(Modification.Combine.serializer(element)) { it is Modification.Combine },
    MySealedClassSerializer.Option(Modification.ModifyByKey.serializer(element)) { it is Modification.ModifyByKey },
    MySealedClassSerializer.Option(Modification.RemoveKeys.serializer(element)) { it is Modification.RemoveKeys },
)
private fun <T> numberOptions(serializer: KSerializer<T>): List<MySealedClassSerializer.Option<Modification<T>, *>> where T: Number, T:Comparable<T> = comparableOptions(serializer) + listOf(
    MySealedClassSerializer.Option(Modification.Increment.serializer(serializer)) { it is Modification.Increment },
    MySealedClassSerializer.Option(Modification.Multiply.serializer(serializer)) { it is Modification.Multiply },
)
private val stringOptions: List<MySealedClassSerializer.Option<Modification<String>, *>>  = comparableOptions(String.serializer()) + listOf(
    MySealedClassSerializer.Option(Modification.AppendString.serializer()) { it is Modification.AppendString },
)
//private fun <T: Any> classOptions(inner: KSerializer<T>, fields: List<SerializableProperty<T, *>>): List<MySealedClassSerializer.Option<Modification<T>, *>> = commonOptions(inner) + fields.map { prop ->
//    MySealedClassSerializer.Option(ModificationOnFieldSerializer(prop)) { it is Modification.OnField<*, *> && it.key.name == prop.name }
//}
private fun <T: Any> classOptionsReflective(inner: KSerializer<T>): List<MySealedClassSerializer.Option<Modification<T>, *>> = commonOptions(inner) + inner.serializableProperties!!.let {
    it.mapIndexed { index, ser ->
        MySealedClassSerializer.Option(ModificationOnFieldSerializer(
            ser
        )) { it is Modification.OnField<*, *> && it.key.name == inner.descriptor.getElementName(index) }
    }
}

private val cache = HashMap<KSerializerKey, MySealedClassSerializerInterface<*>>()
@Suppress("UNCHECKED_CAST")
data class ModificationSerializer<T>(val inner: KSerializer<T>): MySealedClassSerializerInterface<Modification<T>> by (cache.getOrPut(KSerializerKey(inner)) {
    MySealedClassSerializer<Modification<T>>("com.lightningkite.lightningdb.Modification", {
        val r = when {
            inner.nullElement() != null -> nullableOptions(inner.nullElement()!! as KSerializer<Any>)
            inner.descriptor.kind == PrimitiveKind.STRING -> stringOptions
            inner.descriptor.kind == PrimitiveKind.BYTE ||
                    inner.descriptor.kind == PrimitiveKind.SHORT ||
                    inner.descriptor.kind == PrimitiveKind.INT ||
                    inner.descriptor.kind == PrimitiveKind.LONG ||
                    inner.descriptor.kind == PrimitiveKind.FLOAT ||
                    inner.descriptor.kind == PrimitiveKind.DOUBLE -> numberOptions(inner as KSerializer<Int>)
            inner.descriptor.kind == StructureKind.MAP -> stringMapOptions(inner.mapValueElement()!!)
            inner.descriptor.kind == StructureKind.LIST -> {
                if(inner.descriptor.serialName.contains("Set")) setOptions(inner.listElement()!!)
                else listOptions(inner.listElement()!!)
            }
            inner.serializableProperties != null -> classOptionsReflective(inner as KSerializer<Any>)
            else -> comparableOptions(inner as KSerializer<String>)
        }
        r as List<MySealedClassSerializer.Option<Modification<T>, out Modification<T>>>
    })
} as MySealedClassSerializerInterface<Modification<T>>)

class ModificationOnFieldSerializer<K : Any, V>(
    val field: SerializableProperty<K, V>
) : WrappingSerializer<Modification.OnField<K, V>, Modification<V>>(field.name) {
    override fun getDeferred(): KSerializer<Modification<V>> = Modification.serializer(field.serializer)
    override fun inner(it: Modification.OnField<K, V>): Modification<V> = it.modification
    override fun outer(it: Modification<V>): Modification.OnField<K, V> = Modification.OnField(field, it)
}

class ModificationChainSerializer<T>(val inner: KSerializer<T>) :
    WrappingSerializer<Modification.Chain<T>, List<Modification<T>>>("Chain") {
    override fun getDeferred(): KSerializer<List<Modification<T>>> = ListSerializer(Modification.serializer(inner))
    override fun inner(it: Modification.Chain<T>): List<Modification<T>> = it.modifications
    override fun outer(it: List<Modification<T>>): Modification.Chain<T> = Modification.Chain(it)
}

class ModificationIfNotNullSerializer<T>(val inner: KSerializer<T>) :
    WrappingSerializer<Modification.IfNotNull<T>, Modification<T>>("IfNotNull") {
    override fun getDeferred(): KSerializer<Modification<T>> = Modification.serializer(inner)
    override fun inner(it: Modification.IfNotNull<T>): Modification<T> = it.modification
    override fun outer(it: Modification<T>): Modification.IfNotNull<T> = Modification.IfNotNull(it)
}

class ModificationAssignSerializer<T>(val inner: KSerializer<T>) :
    WrappingSerializer<Modification.Assign<T>, T>("Assign") {
    override fun getDeferred(): KSerializer<T> = inner
    override fun inner(it: Modification.Assign<T>): T = it.value
    override fun outer(it: T): Modification.Assign<T> = Modification.Assign(it)
}

class ModificationCoerceAtMostSerializer<T : Comparable<T>>(val inner: KSerializer<T>) :
    WrappingSerializer<Modification.CoerceAtMost<T>, T>("CoerceAtMost") {
    override fun getDeferred(): KSerializer<T> = inner
    override fun inner(it: Modification.CoerceAtMost<T>): T = it.value
    override fun outer(it: T): Modification.CoerceAtMost<T> = Modification.CoerceAtMost(it)
}

class ModificationCoerceAtLeastSerializer<T : Comparable<T>>(val inner: KSerializer<T>) :
    WrappingSerializer<Modification.CoerceAtLeast<T>, T>("CoerceAtLeast") {
    override fun getDeferred(): KSerializer<T> = inner
    override fun inner(it: Modification.CoerceAtLeast<T>): T = it.value
    override fun outer(it: T): Modification.CoerceAtLeast<T> = Modification.CoerceAtLeast(it)
}

class ModificationIncrementSerializer<T : Number>(val inner: KSerializer<T>) :
    WrappingSerializer<Modification.Increment<T>, T>("Increment") {
    override fun getDeferred(): KSerializer<T> = inner
    override fun inner(it: Modification.Increment<T>): T = it.by
    override fun outer(it: T): Modification.Increment<T> = Modification.Increment(it)
}

class ModificationMultiplySerializer<T : Number>(val inner: KSerializer<T>) :
    WrappingSerializer<Modification.Multiply<T>, T>("Multiply") {
    override fun getDeferred(): KSerializer<T> = inner
    override fun inner(it: Modification.Multiply<T>): T = it.by
    override fun outer(it: T): Modification.Multiply<T> = Modification.Multiply(it)
}

object ModificationAppendStringSerializer : WrappingSerializer<Modification.AppendString, String>("AppendString") {
    override fun getDeferred(): KSerializer<String> = String.serializer()
    override fun inner(it: Modification.AppendString): String = it.value
    override fun outer(it: String): Modification.AppendString = Modification.AppendString(it)
}

class ModificationListAppendSerializer<T>(val inner: KSerializer<T>) :
    WrappingSerializer<Modification.ListAppend<T>, List<T>>("ListAppend") {
    override fun getDeferred() = ListSerializer(inner)
    override fun inner(it: Modification.ListAppend<T>): List<T> = it.items
    override fun outer(it: List<T>): Modification.ListAppend<T> = Modification.ListAppend(it)
}

class ModificationSetAppendSerializer<T>(val inner: KSerializer<T>) :
    WrappingSerializer<Modification.SetAppend<T>, Set<T>>("SetAppend") {
    override fun getDeferred() = SetSerializer(inner)
    override fun inner(it: Modification.SetAppend<T>): Set<T> = it.items
    override fun outer(it: Set<T>): Modification.SetAppend<T> = Modification.SetAppend(it)
}

class ModificationListRemoveSerializer<T>(val inner: KSerializer<T>) :
    WrappingSerializer<Modification.ListRemove<T>, Condition<T>>("ListRemove") {
    override fun getDeferred() = Condition.serializer(inner)
    override fun inner(it: Modification.ListRemove<T>): Condition<T> = it.condition
    override fun outer(it: Condition<T>): Modification.ListRemove<T> = Modification.ListRemove(it)
}

class ModificationSetRemoveSerializer<T>(val inner: KSerializer<T>) :
    WrappingSerializer<Modification.SetRemove<T>, Condition<T>>("SetRemove") {
    override fun getDeferred() = Condition.serializer(inner)
    override fun inner(it: Modification.SetRemove<T>): Condition<T> = it.condition
    override fun outer(it: Condition<T>): Modification.SetRemove<T> = Modification.SetRemove(it)
}

class ModificationListRemoveInstancesSerializer<T>(val inner: KSerializer<T>) :
    WrappingSerializer<Modification.ListRemoveInstances<T>, List<T>>("ListRemoveInstances") {
    override fun getDeferred() = ListSerializer(inner)
    override fun inner(it: Modification.ListRemoveInstances<T>): List<T> = it.items
    override fun outer(it: List<T>): Modification.ListRemoveInstances<T> = Modification.ListRemoveInstances(it)
}

class ModificationSetRemoveInstancesSerializer<T>(val inner: KSerializer<T>) :
    WrappingSerializer<Modification.SetRemoveInstances<T>, Set<T>>("SetRemoveInstances") {
    override fun getDeferred() = SetSerializer(inner)
    override fun inner(it: Modification.SetRemoveInstances<T>): Set<T> = it.items
    override fun outer(it: Set<T>): Modification.SetRemoveInstances<T> = Modification.SetRemoveInstances(it)
}

class ModificationCombineSerializer<T>(val inner: KSerializer<T>) :
    WrappingSerializer<Modification.Combine<T>, Map<String, T>>("Combine") {
    override fun getDeferred(): KSerializer<Map<String, T>> = MapSerializer(serializer<String>(), inner)
    override fun inner(it: Modification.Combine<T>): Map<String, T> = it.map
    override fun outer(it: Map<String, T>): Modification.Combine<T> = Modification.Combine(it)
}

class ModificationModifyByKeySerializer<T>(val inner: KSerializer<T>) :
    WrappingSerializer<Modification.ModifyByKey<T>, Map<String, Modification<T>>>("ModifyByKey") {
    override fun getDeferred(): KSerializer<Map<String, Modification<T>>> =
        MapSerializer(serializer<String>(), Modification.serializer(inner))

    override fun inner(it: Modification.ModifyByKey<T>): Map<String, Modification<T>> = it.map
    override fun outer(it: Map<String, Modification<T>>): Modification.ModifyByKey<T> = Modification.ModifyByKey(it)
}

class ModificationRemoveKeysSerializer<T>(val inner: KSerializer<T>) :
    WrappingSerializer<Modification.RemoveKeys<T>, Set<String>>("RemoveKeys") {
    override fun getDeferred(): KSerializer<Set<String>> = SetSerializer(serializer<String>())
    override fun inner(it: Modification.RemoveKeys<T>): Set<String> = it.fields
    override fun outer(it: Set<String>): Modification.RemoveKeys<T> = Modification.RemoveKeys(it)
}

class ModificationListDropFirstSerializer<T>(val inner: KSerializer<T>) :
    WrappingSerializer<Modification.ListDropFirst<T>, Boolean>("ListDropFirst") {
    override fun getDeferred(): KSerializer<Boolean> = Boolean.serializer()
    override fun inner(it: Modification.ListDropFirst<T>): Boolean = true
    override fun outer(it: Boolean): Modification.ListDropFirst<T> = Modification.ListDropFirst()
}

class ModificationSetDropFirstSerializer<T>(val inner: KSerializer<T>) :
    WrappingSerializer<Modification.SetDropFirst<T>, Boolean>("SetDropFirst") {
    override fun getDeferred(): KSerializer<Boolean> = Boolean.serializer()
    override fun inner(it: Modification.SetDropFirst<T>): Boolean = true
    override fun outer(it: Boolean): Modification.SetDropFirst<T> = Modification.SetDropFirst()
}

class ModificationListDropLastSerializer<T>(val inner: KSerializer<T>) :
    WrappingSerializer<Modification.ListDropLast<T>, Boolean>("ListDropLast") {
    override fun getDeferred(): KSerializer<Boolean> = Boolean.serializer()
    override fun inner(it: Modification.ListDropLast<T>): Boolean = true
    override fun outer(it: Boolean): Modification.ListDropLast<T> = Modification.ListDropLast()
}

class ModificationSetDropLastSerializer<T>(val inner: KSerializer<T>) :
    WrappingSerializer<Modification.SetDropLast<T>, Boolean>("SetDropLast") {
    override fun getDeferred(): KSerializer<Boolean> = Boolean.serializer()
    override fun inner(it: Modification.SetDropLast<T>): Boolean = true
    override fun outer(it: Boolean): Modification.SetDropLast<T> = Modification.SetDropLast()
}

