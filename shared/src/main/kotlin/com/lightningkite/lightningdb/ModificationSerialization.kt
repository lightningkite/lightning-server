@file:OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)

package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.IsEquatable
import com.lightningkite.khrysalis.fatalError
import kotlinx.serialization.*
import kotlin.reflect.KClass
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.internal.GeneratedSerializer
import java.util.*
import kotlin.reflect.KProperty1
import kotlin.reflect.full.allSupertypes
import kotlin.reflect.jvm.jvmErasure

private val serializers = HashMap<KSerializer<*>, MySealedClassSerializerInterface<*>>()

@Suppress("UNCHECKED_CAST")
private fun <Inner> getMod(inner: KSerializer<Inner>): MySealedClassSerializerInterface<Modification<Inner>> = serializers.getOrPut(inner) {
    MySealedClassSerializer(
        "com.lightningkite.lightningdb.Modification<${inner.descriptor.serialName}>",
        Modification::class as KClass<Modification<Inner>>,
        {
            val map = LinkedHashMap<String, KSerializer<out Modification<Inner>>>()
            fun register(serializer: KSerializer<out Modification<*>>) {
                map[serializer.descriptor.serialName] = (serializer as KSerializer<out Modification<Inner>>)
            }
            register(Modification.Chain.serializer(inner))
            register(Modification.IfNotNull.serializer(inner))
            register(Modification.Assign.serializer(inner))
            register(Modification.CoerceAtMost.serializer(inner))
            register(Modification.CoerceAtLeast.serializer(inner))
            register(Modification.Increment.serializer(inner))
            register(Modification.Multiply.serializer(inner))
            if (inner == String.serializer()) register(Modification.AppendString.serializer())
            if (inner.descriptor.kind == StructureKind.LIST) {
                inner.listElement()?.let { element ->
                    register(Modification.SetAppend.serializer(element))
                    register(Modification.SetRemove.serializer(element))
                    register(Modification.SetRemoveInstances.serializer(element))
                    register(Modification.SetDropFirst.serializer(element))
                    register(Modification.SetDropLast.serializer(element))
                    register(Modification.SetPerElement.serializer(element))
                    register(Modification.ListAppend.serializer(element))
                    register(Modification.ListRemove.serializer(element))
                    register(Modification.ListRemoveInstances.serializer(element))
                    register(Modification.ListDropFirst.serializer(element))
                    register(Modification.ListDropLast.serializer(element))
                    register(Modification.ListPerElement.serializer(element))
                }
            }
            if (inner.descriptor.kind == StructureKind.MAP) {
                inner.mapValueElement()?.let { element ->
                    register(Modification.Combine.serializer(element))
                    register(Modification.ModifyByKey.serializer(element))
                    register(Modification.RemoveKeys.serializer(element))
                }
            }
            if (inner is GeneratedSerializer<*> && inner.descriptor.kind == StructureKind.CLASS && inner !is MySealedClassSerializerInterface) {
                val childSerializers = inner.childSerializers()
                val fields = inner.attemptGrabFields()
                for (index in 0 until inner.descriptor.elementsCount) {
                    val name = inner.descriptor.getElementName(index)
                    val prop = fields[name]!!.property
                    register(
                        OnFieldSerializer2<Any, Any?>(
                            prop as KProperty1<Any, Any?>,
                            Modification.serializer(childSerializers[index]) as KSerializer<Modification<Any?>>
                        )
                    )
                }
            }
            if (inner.descriptor.isNullable) {
                inner.nullElement()?.let { element ->
                    register(Modification.IfNotNull.serializer(element))
                }
            }
            map
        },
        annotations = Modification::class.annotations,
        alternateReadNames = mapOf(
            "AppendList" to "ListAppend",
            "AppendSet" to "SetAppend",
            "Remove" to "ListRemove",
            "RemoveInstances" to "ListRemoveInstances",
            "DropFirst" to "ListDropFirst",
            "DropLast" to "ListDropLast",
            "PerElement" to "ListPerElement",
        )
    ) {
        when (it) {
            is Modification.Chain -> "Chain"
            is Modification.IfNotNull<*> -> "IfNotNull"
            is Modification.Assign -> "Assign"
            is Modification.CoerceAtMost -> "CoerceAtMost"
            is Modification.CoerceAtLeast -> "CoerceAtLeast"
            is Modification.Increment -> "Increment"
            is Modification.Multiply -> "Multiply"
            is Modification.AppendString -> "AppendString"
            is Modification.ListAppend<*> -> "ListAppend"
            is Modification.ListRemove<*> -> "ListRemove"
            is Modification.ListRemoveInstances<*> -> "ListRemoveInstances"
            is Modification.ListDropFirst<*> -> "ListDropFirst"
            is Modification.ListDropLast<*> -> "ListDropLast"
            is Modification.ListPerElement<*> -> "ListPerElement"
            is Modification.SetAppend<*> -> "SetAppend"
            is Modification.SetRemove<*> -> "SetRemove"
            is Modification.SetRemoveInstances<*> -> "SetRemoveInstances"
            is Modification.SetDropFirst<*> -> "SetDropFirst"
            is Modification.SetDropLast<*> -> "SetDropLast"
            is Modification.SetPerElement<*> -> "SetPerElement"
            is Modification.Combine<*> -> "Combine"
            is Modification.ModifyByKey<*> -> "ModifyByKey"
            is Modification.RemoveKeys<*> -> "RemoveKeys"
            is Modification.OnField<*, *> -> it.key.name
            else -> fatalError()
        }
    }
} as MySealedClassSerializerInterface<Modification<Inner>>

class ModificationSerializer<Inner>(val inner: KSerializer<Inner>) : MySealedClassSerializerInterface<Modification<Inner>> by getMod(inner)

class OnFieldSerializer2<K : Any, V>(
    val field: KProperty1<K, V>,
    val conditionSerializer: KSerializer<Modification<V>>
) : WrappingSerializer<Modification.OnField<K, V>, Modification<V>>(field.name) {
    override fun getDeferred(): KSerializer<Modification<V>> = conditionSerializer
    override fun inner(it: Modification.OnField<K, V>): Modification<V> = it.modification
    override fun outer(it: Modification<V>): Modification.OnField<K, V> = Modification.OnField(field, it)
}

class ModificationChainSerializer<T>(val inner: KSerializer<T>): WrappingSerializer<Modification.Chain<T>, List<Modification<T>>>("Chain") {
    override fun getDeferred(): KSerializer<List<Modification<T>>> = ListSerializer(Modification.serializer(inner))
    override fun inner(it: Modification.Chain<T>): List<Modification<T>> = it.modifications
    override fun outer(it: List<Modification<T>>): Modification.Chain<T> = Modification.Chain(it)
}

class ModificationIfNotNullSerializer<T>(val inner: KSerializer<T>) : WrappingSerializer<Modification.IfNotNull<T>, Modification<T>>("IfNotNull") {
    override fun getDeferred(): KSerializer<Modification<T>> = Modification.serializer(inner)
    override fun inner(it: Modification.IfNotNull<T>): Modification<T> = it.modification
    override fun outer(it: Modification<T>): Modification.IfNotNull<T> = Modification.IfNotNull(it)
}

class ModificationAssignSerializer<T>(val inner: KSerializer<T>) : WrappingSerializer<Modification.Assign<T>, T>("Assign") {
    override fun getDeferred(): KSerializer<T> = inner
    override fun inner(it: Modification.Assign<T>): T = it.value
    override fun outer(it: T): Modification.Assign<T> = Modification.Assign(it)
}

class ModificationCoerceAtMostSerializer<T: Comparable<T>>(val inner: KSerializer<T>) : WrappingSerializer<Modification.CoerceAtMost<T>, T>("CoerceAtMost") {
    override fun getDeferred(): KSerializer<T> = inner
    override fun inner(it: Modification.CoerceAtMost<T>): T = it.value
    override fun outer(it: T): Modification.CoerceAtMost<T> = Modification.CoerceAtMost(it)
}

class ModificationCoerceAtLeastSerializer<T: Comparable<T>>(val inner: KSerializer<T>) : WrappingSerializer<Modification.CoerceAtLeast<T>, T>("CoerceAtLeast") {
    override fun getDeferred(): KSerializer<T> = inner
    override fun inner(it: Modification.CoerceAtLeast<T>): T = it.value
    override fun outer(it: T): Modification.CoerceAtLeast<T> = Modification.CoerceAtLeast(it)
}

class ModificationIncrementSerializer<T : Number>(val inner: KSerializer<T>) : WrappingSerializer<Modification.Increment<T>, T>("Increment") {
    override fun getDeferred(): KSerializer<T> = inner
    override fun inner(it: Modification.Increment<T>): T = it.by
    override fun outer(it: T): Modification.Increment<T> = Modification.Increment(it)
}

class ModificationMultiplySerializer<T : Number>(val inner: KSerializer<T>) : WrappingSerializer<Modification.Multiply<T>, T>("Multiply") {
    override fun getDeferred(): KSerializer<T> = inner
    override fun inner(it: Modification.Multiply<T>): T = it.by
    override fun outer(it: T): Modification.Multiply<T> = Modification.Multiply(it)
}

object ModificationAppendStringSerializer : WrappingSerializer<Modification.AppendString, String>("AppendString") {
    override fun getDeferred(): KSerializer<String> = String.serializer()
    override fun inner(it: Modification.AppendString): String = it.value
    override fun outer(it: String): Modification.AppendString = Modification.AppendString(it)
}

class ModificationListAppendSerializer<T>(val inner: KSerializer<T>) : WrappingSerializer<Modification.ListAppend<T>, List<T>>("ListAppend") {
    override fun getDeferred() = ListSerializer(inner)
    override fun inner(it: Modification.ListAppend<T>): List<T> = it.items
    override fun outer(it: List<T>): Modification.ListAppend<T> = Modification.ListAppend(it)
}

class ModificationSetAppendSerializer<T>(val inner: KSerializer<T>) : WrappingSerializer<Modification.SetAppend<T>, Set<T>>("SetAppend") {
    override fun getDeferred() = SetSerializer(inner)
    override fun inner(it: Modification.SetAppend<T>): Set<T> = it.items
    override fun outer(it: Set<T>): Modification.SetAppend<T> = Modification.SetAppend(it)
}

class ModificationListRemoveSerializer<T>(val inner: KSerializer<T>) : WrappingSerializer<Modification.ListRemove<T>, Condition<T>>("ListRemove") {
    override fun getDeferred() = Condition.serializer(inner)
    override fun inner(it: Modification.ListRemove<T>): Condition<T> = it.condition
    override fun outer(it: Condition<T>): Modification.ListRemove<T> = Modification.ListRemove(it)
}

class ModificationSetRemoveSerializer<T>(val inner: KSerializer<T>) : WrappingSerializer<Modification.SetRemove<T>, Condition<T>>("SetRemove") {
    override fun getDeferred() = Condition.serializer(inner)
    override fun inner(it: Modification.SetRemove<T>): Condition<T> = it.condition
    override fun outer(it: Condition<T>): Modification.SetRemove<T> = Modification.SetRemove(it)
}

class ModificationListRemoveInstancesSerializer<T>(val inner: KSerializer<T>) : WrappingSerializer<Modification.ListRemoveInstances<T>, List<T>>("ListRemoveInstances") {
    override fun getDeferred() = ListSerializer(inner)
    override fun inner(it: Modification.ListRemoveInstances<T>): List<T> = it.items
    override fun outer(it: List<T>): Modification.ListRemoveInstances<T> = Modification.ListRemoveInstances(it)
}

class ModificationSetRemoveInstancesSerializer<T>(val inner: KSerializer<T>) : WrappingSerializer<Modification.SetRemoveInstances<T>, Set<T>>("SetRemoveInstances") {
    override fun getDeferred() = SetSerializer(inner)
    override fun inner(it: Modification.SetRemoveInstances<T>): Set<T> = it.items
    override fun outer(it: Set<T>): Modification.SetRemoveInstances<T> = Modification.SetRemoveInstances(it)
}

class ModificationCombineSerializer<T>(val inner: KSerializer<T>) : WrappingSerializer<Modification.Combine<T>, Map<String, T>>("Combine") {
    override fun getDeferred(): KSerializer<Map<String, T>> = MapSerializer(serializer<String>(), inner)
    override fun inner(it: Modification.Combine<T>): Map<String, T> = it.map
    override fun outer(it: Map<String, T>): Modification.Combine<T> = Modification.Combine(it)
}

class ModificationModifyByKeySerializer<T>(val inner: KSerializer<T>) : WrappingSerializer<Modification.ModifyByKey<T>, Map<String, Modification<T>>>("ModifyByKey") {
    override fun getDeferred(): KSerializer<Map<String, Modification<T>>> = MapSerializer(serializer<String>(), Modification.serializer(inner))
    override fun inner(it: Modification.ModifyByKey<T>): Map<String, Modification<T>> = it.map
    override fun outer(it: Map<String, Modification<T>>): Modification.ModifyByKey<T> = Modification.ModifyByKey(it)
}

class ModificationRemoveKeysSerializer<T>(val inner: KSerializer<T>) : WrappingSerializer<Modification.RemoveKeys<T>, Set<String>>("RemoveKeys") {
    override fun getDeferred(): KSerializer<Set<String>> = SetSerializer(serializer<String>())
    override fun inner(it: Modification.RemoveKeys<T>): Set<String> = it.fields
    override fun outer(it: Set<String>): Modification.RemoveKeys<T> = Modification.RemoveKeys(it)
}

class ModificationListDropFirstSerializer<T>(val inner: KSerializer<T>) : WrappingSerializer<Modification.ListDropFirst<T>, Boolean>("ListDropFirst") {
    override fun getDeferred(): KSerializer<Boolean> = Boolean.serializer()
    override fun inner(it: Modification.ListDropFirst<T>): Boolean = true
    override fun outer(it: Boolean): Modification.ListDropFirst<T> = Modification.ListDropFirst()
}

class ModificationSetDropFirstSerializer<T>(val inner: KSerializer<T>) : WrappingSerializer<Modification.SetDropFirst<T>, Boolean>("SetDropFirst") {
    override fun getDeferred(): KSerializer<Boolean> = Boolean.serializer()
    override fun inner(it: Modification.SetDropFirst<T>): Boolean = true
    override fun outer(it: Boolean): Modification.SetDropFirst<T> = Modification.SetDropFirst()
}

class ModificationListDropLastSerializer<T>(val inner: KSerializer<T>) : WrappingSerializer<Modification.ListDropLast<T>, Boolean>("ListDropLast") {
    override fun getDeferred(): KSerializer<Boolean> = Boolean.serializer()
    override fun inner(it: Modification.ListDropLast<T>): Boolean = true
    override fun outer(it: Boolean): Modification.ListDropLast<T> = Modification.ListDropLast()
}

class ModificationSetDropLastSerializer<T>(val inner: KSerializer<T>) : WrappingSerializer<Modification.SetDropLast<T>, Boolean>("SetDropLast") {
    override fun getDeferred(): KSerializer<Boolean> = Boolean.serializer()
    override fun inner(it: Modification.SetDropLast<T>): Boolean = true
    override fun outer(it: Boolean): Modification.SetDropLast<T> = Modification.SetDropLast()
}

