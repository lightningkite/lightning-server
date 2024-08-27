package com.lightningkite.validation

import com.lightningkite.IsRawString
import com.lightningkite.lightningdb.*
import com.lightningkite.serialization.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.SerializersModule
import kotlin.reflect.KClass

interface ShouldValidateSub<A> : KSerializer<A> {
    fun validate(value: A, existingAnnotations: List<Annotation>, defer: (Any?, List<Annotation>) -> Unit) =
        defer(value, existingAnnotations)
}

typealias ValidationOut = (ValidationIssue) -> Unit

@Serializable
data class ValidationIssue(val path: List<String>, val code: Int, val text: String)

@Serializable
data class ValidationIssuePart(val code: Int, val text: String)

fun <T> SerializersModule.validateFast(serializer: SerializationStrategy<T>, value: T, out: ValidationOut) {
    val e = ValidationEncoder(this, out)
    e.encodeSerializableValue(serializer, value)
}

suspend fun <T> SerializersModule.validate(serializer: SerializationStrategy<T>, value: T, out: ValidationOut) {
    val e = ValidationEncoder(this, out)
    e.encodeSerializableValue(serializer, value)
    e.runSuspend()
}

//fun <T> SerializersModule.validateOrThrow(serializer: SerializationStrategy<T>, value: T) {
//    val issues = ArrayList<Pair<List<String>, String>>()
//    ValidationEncoder(this, { a, b ->
//        issues.add(a to b)
//    }).encodeSerializableValue(serializer, value)
//    if(issues.isNotEmpty()) {
//        throw BadRe
//    }
//}

object Validators {
    internal val processors = ArrayList<(Annotation, value: Any?) -> ValidationIssuePart?>()
    inline fun <reified T : Annotation, V : Any> processor(crossinline action: (T, V) -> ValidationIssuePart?) {
        directProcessor(T::class) { a, b ->
            if (a is T) {
                @Suppress("UNCHECKED_CAST")
                if(b is Collection<*>)
                    b.asSequence().mapNotNull { action(a, it as V) }.firstOrNull()
                else if(b is Map<*, *>)
                    b.values.asSequence().mapNotNull { action(a, it as V) }.firstOrNull()
                else action(a, b as V)
            } else
                null
        }
    }

    inline fun <reified T : Annotation, V : Any> directProcessor(crossinline action: (T, V) -> ValidationIssuePart?) {
        directProcessor(T::class) { a, b ->
            @Suppress("UNCHECKED_CAST")
            if (a is T) action(a, b as V) else null
        }
    }

    fun directProcessor(@Suppress("UNUSED_PARAMETER") type: KClass<out Annotation>, action: (Annotation, Any?) -> ValidationIssuePart?) {
        processors.add(action)
    }

    internal val suspendProcessors = ArrayList<suspend (Annotation, value: Any?) -> ValidationIssuePart?>()
    inline fun <reified T : Annotation, V : Any> suspendProcessor(crossinline action: suspend (T, V) -> ValidationIssuePart?) {
        directSuspendProcessor(T::class) { a, b ->
            if (a is T) {
                @Suppress("UNCHECKED_CAST")
                if(b is Collection<*>)
                    b.mapNotNull { action(a, it as V) }.firstOrNull()
                else if(b is Map<*, *>)
                    b.values.mapNotNull { action(a, it as V) }.firstOrNull()
                else action(a, b as V)
            } else
                null
        }
    }
    inline fun <reified T : Annotation, V : Any> directSuspendProcessor(crossinline action: suspend (T, V) -> ValidationIssuePart?) {
        directSuspendProcessor(T::class) { a, b ->
            @Suppress("UNCHECKED_CAST")
            if (a is T) action(a, b as V) else null
        }
    }
    fun directSuspendProcessor(@Suppress("UNUSED_PARAMETER") type: KClass<out Annotation>, action: suspend (Annotation, Any?) -> ValidationIssuePart?) {
        suspendProcessors.add(action)
    }

    init {
        directProcessor<MaxSize, Any> { t, v ->
            when (v) {
                is Collection<*> -> if (v.size > t.size) ValidationIssuePart(
                    1,
                    "Too long; got ${v.size} items but have a maximum of ${t.size} items."
                ) else null

                is Map<*, *> -> if (v.size > t.size) ValidationIssuePart(
                    1,
                    "Too long; got ${v.size} entries but have a maximum of ${t.size} entries."
                ) else null

                else -> throw NotImplementedError("Unknown type ${v::class}")
            }
        }
        processor<MaxLength, Any> { t, v ->
            when (v) {
                is String -> if (v.length > t.size) ValidationIssuePart(
                    1,
                    "Too long; maximum ${t.size} characters allowed"
                ) else null

                is IsRawString -> if (v.raw.length > t.size) ValidationIssuePart(
                    1,
                    "Too long; maximum ${t.size} characters allowed"
                ) else null

                else -> throw NotImplementedError("Unknown type ${v::class}")
            }
        }
        processor<ExpectedPattern, Any> { t, v ->
            when (v) {
                is String -> if (!Regex(t.pattern).matches(v)) ValidationIssuePart(
                    2,
                    "Does not match pattern; expected to match ${t.pattern}"
                ) else null

                is IsRawString -> if (!Regex(t.pattern).matches(v.raw)) ValidationIssuePart(
                    2,
                    "Does not match pattern; expected to match ${t.pattern}"
                ) else null

                else -> throw NotImplementedError("Unknown type ${v::class}")
            }
        }
        processor<IntegerRange, Any> { t, v ->
            when (v) {
                is Byte -> if (v !in t.min..t.max) ValidationIssuePart(
                    1,
                    "Out of range; expected to be between ${t.min} and ${t.max}"
                ) else null

                is Short -> if (v !in t.min..t.max) ValidationIssuePart(
                    1,
                    "Out of range; expected to be between ${t.min} and ${t.max}"
                ) else null

                is Int -> if (v !in t.min..t.max) ValidationIssuePart(
                    1,
                    "Out of range; expected to be between ${t.min} and ${t.max}"
                ) else null

                is Long -> if (v !in t.min..t.max) ValidationIssuePart(
                    1,
                    "Out of range; expected to be between ${t.min} and ${t.max}"
                ) else null

                is UByte -> if (v.toLong() !in t.min..t.max) ValidationIssuePart(
                    1,
                    "Out of range; expected to be between ${t.min} and ${t.max}"
                ) else null

                is UShort -> if (v.toLong() !in t.min..t.max) ValidationIssuePart(
                    1,
                    "Out of range; expected to be between ${t.min} and ${t.max}"
                ) else null

                is UInt -> if (v.toLong() !in t.min..t.max) ValidationIssuePart(
                    1,
                    "Out of range; expected to be between ${t.min} and ${t.max}"
                ) else null

                is ULong -> if (v.toLong() !in t.min..t.max) ValidationIssuePart(
                    1,
                    "Out of range; expected to be between ${t.min} and ${t.max}"
                ) else null

                else -> throw NotImplementedError("Unknown type ${v::class}")
            }
        }
        processor<FloatRange, Any> { t, v ->
            when (v) {
                is Float -> if (v !in t.min..t.max) ValidationIssuePart(
                    1,
                    "Out of range; expected to be between ${t.min} and ${t.max}"
                ) else null

                is Double -> if (v !in t.min..t.max) ValidationIssuePart(
                    1,
                    "Out of range; expected to be between ${t.min} and ${t.max}"
                ) else null

                else -> throw NotImplementedError("Unknown type ${v::class}")
            }
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
private class ValidationEncoder(override val serializersModule: SerializersModule, val out: ValidationOut) :
    AbstractEncoder() {

    val queued = ArrayList<suspend () -> Unit>()
    val keyPath = ArrayList<Pair<SerialDescriptor, Int>>()
    var lastDescriptor: SerialDescriptor? = null
    var lastElementIndex: Int = 0

    suspend fun runSuspend() {
        queued.forEach { it() }
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        lastDescriptor?.let {
            keyPath.add(it to lastElementIndex)
        }
        return super.beginStructure(descriptor)
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        keyPath.removeLastOrNull()
        super.endStructure(descriptor)
    }

    var next: List<Annotation> = emptyList()
    override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
        next = descriptor.getElementAnnotations(index)
        lastElementIndex = index
        lastDescriptor = descriptor
        return super.encodeElement(descriptor, index)
    }

    override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
        if (serializer is ShouldValidateSub<T>) serializer.validate(
            value,
            lastDescriptor?.getElementAnnotations(lastElementIndex) ?: listOf()
        ) { a, b -> if (a != null) validate(a, b) }
        else if (next.isNotEmpty() && value != null) validate(
            value,
            lastDescriptor?.getElementAnnotations(lastElementIndex) ?: listOf()
        )
        next = emptyList()
        super.encodeSerializableValue(serializer, value)
    }

    override fun encodeValue(value: Any) {
        if (next.isNotEmpty()) validate(value, lastDescriptor?.getElementAnnotations(lastElementIndex) ?: listOf())
        next = emptyList()
    }

    override fun encodeNull() {
        next = emptyList()
    }

    fun validate(value: Any, annotations: List<Annotation>) {
        annotations.forEach {
            Validators.processors.forEach { runner ->
                runner.invoke(it, value)?.let {
                    out(ValidationIssue(buildList {
                        keyPath.forEach {
                            add(it.first.getElementName(it.second))
                        }
                        add(lastDescriptor?.takeIf { lastElementIndex < it.elementsCount }
                            ?.getElementName(lastElementIndex) ?: lastElementIndex.toString())
                    }, it.code, it.text))
                }
            }
            queued += {
                Validators.suspendProcessors.forEach { runner ->
                    runner.invoke(it, value)?.let {
                        out(ValidationIssue(buildList {
                            keyPath.forEach {
                                add(it.first.getElementName(it.second))
                            }
                            add(lastDescriptor?.takeIf { lastElementIndex < it.elementsCount }
                                ?.getElementName(lastElementIndex) ?: lastElementIndex.toString())
                        }, it.code, it.text))
                    }
                }
            }
        }
    }
}