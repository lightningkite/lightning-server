package com.lightningkite.lightningserver.scim

import com.lightningkite.lightningdb.Condition
import com.lightningkite.lightningdb.SortPart
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.exceptions.RawHttpStatusException
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.scim.parse.ScimFilterLexer
import com.lightningkite.lightningserver.scim.parse.ScimFilterParser
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.serialization.SerializableProperty
import com.lightningkite.serialization.description
import com.lightningkite.serialization.innerElement
import com.lightningkite.serialization.serializableProperties
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.StringFormat
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import org.antlr.v4.kotlinruntime.CommonTokenStream
import org.antlr.v4.kotlinruntime.StringCharStream


class ScimPathPartialSerializer<T>(val subtype: KSerializer<T>) : KSerializer<ScimPathPartial<T>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.lightningkite.lightningserver.scim.ScimPathPartial", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: ScimPathPartial<T>
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): ScimPathPartial<T> = fromString(decoder.decodeString())

    fun fromString(string: String): ScimPathPartial<T> {
        var current: ScimPath<T, *> = ScimPath.Base<T>(subtype)
        val firstAttr = string.takeWhile { it.isLetterOrDigit() }
        @Suppress("UNCHECKED_CAST")
        current = ((current.serializer.serializableProperties
            ?: throw IllegalArgumentException("No subfields available on ${current.serializer.descriptor.serialName}")).find {
            it.name.equals(
                firstAttr,
                true
            )
        } as? SerializableProperty<Any?, Any?>
            ?: throw IllegalArgumentException("Could not find a field named ${firstAttr} on ${current.serializer.descriptor.serialName}.  Available fields: ${current.serializer.serializableProperties!!.joinToString() { it.name }}")).let { field ->
            if (field.serializer.descriptor.isNullable)
                ScimPath.FieldNullable(current as ScimPath<T, Any?>, field)
            else
                ScimPath.Field(current as ScimPath<T, Any?>, field)
        }
        var remainingString = string.dropWhile { it.isLetter() }
        while (remainingString.isNotEmpty()) {
            when (remainingString[0]) {
                '.' -> {
                    remainingString = remainingString.drop(1)
                    val name = remainingString.takeWhile { it.isLetterOrDigit() }
                    remainingString = remainingString.dropWhile { it.isLetterOrDigit() }
                    @Suppress("UNCHECKED_CAST")
                    current = ((current.serializer.serializableProperties
                        ?: throw IllegalArgumentException("No subfields available on ${current.serializer.descriptor.serialName}")).find {
                        it.name.equals(
                            name,
                            true
                        )
                    } as? SerializableProperty<Any?, Any?>
                        ?: throw IllegalArgumentException("Could not find a field named ${name} on ${current.serializer.descriptor.serialName}.  Available fields: ${current.serializer.serializableProperties!!.joinToString() { it.name }}")).let { field ->
                        if (field.serializer.descriptor.isNullable)
                            ScimPath.FieldNullable(current as ScimPath<T, Any?>, field)
                        else
                            ScimPath.Field(current as ScimPath<T, Any?>, field)
                    }
                }

                '[' -> {
                    remainingString = remainingString.drop(1)
                    val (condition, end) = ScimConditionSerializer(current.serializer.innerElement()).fromStringUntilTermination(
                        remainingString
                    )
                    remainingString = remainingString.drop(end)
                    @Suppress("UNCHECKED_CAST")
                    current = ScimPath.Filter<T, Collection<Any?>, Any?>(
                        current as ScimPath<T, Collection<Any?>>,
                        condition as Condition<Any?>
                    )
                }
            }
        }
        return current
    }
}

/**
 * See https://datatracker.ietf.org/doc/html/rfc7644#autoid-17
 */
class ScimConditionSerializer<T>(val subtype: KSerializer<T>) : KSerializer<Condition<T>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.lightningkite.lightningdb.Condition/scim", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Condition<T>
    ) {
        TODO("Not yet implemented")
    }

    override fun deserialize(decoder: Decoder): Condition<T> {
        val string = decoder.decodeString()

        val str = StringCharStream("meta.lastModified gt \"2011-05-13T04:42:34Z\"")
        val lex = ScimFilterLexer(str)
        val stream = CommonTokenStream(lex)
        val parse = ScimFilterParser(stream)

        return parse.filter().toCondition()
    }

    private fun ScimFilterParser.AndExpContext.toCondition(): Condition<T> = TODO()
    private fun ScimFilterParser.ValPathExpContext.toCondition(): Condition<T> = TODO()
    private fun ScimFilterParser.PresentExpContext.toCondition(): Condition<T> = TODO()
    private fun ScimFilterParser.OperatorExpContext.toCondition(): Condition<T> = TODO()
    private fun ScimFilterParser.BraceExpContext.toCondition(): Condition<T> = TODO()
    private fun ScimFilterParser.OrExpContext.toCondition(): Condition<T> = TODO()
    private fun ScimFilterParser.FilterContext.toCondition(): Condition<T> {
        return when(val element = this) {
            is ScimFilterParser.AndExpContext -> element.toCondition()
            is ScimFilterParser.ValPathExpContext -> element.toCondition()
            is ScimFilterParser.PresentExpContext -> element.toCondition()
            is ScimFilterParser.OperatorExpContext -> element.toCondition()
            is ScimFilterParser.BraceExpContext -> element.toCondition()
            is ScimFilterParser.OrExpContext -> element.toCondition()
            else -> throw IllegalArgumentException("No filter found")
        }
    }

    fun fromString(string: String): Condition<T> = TODO()
    fun fromStringUntilTermination(string: String, start: Int = 0): Pair<Condition<T>, Int> = TODO()
}

/**
 * See https://datatracker.ietf.org/doc/html/rfc7644#autoid-17
 */
class ScimSortPartSerializer<T>(val subtype: KSerializer<T>) : KSerializer<SortPart<T>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.lightningkite.lightningdb.SortPart/scim", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: SortPart<T>
    ) {
        TODO("Not yet implemented")
    }

    override fun deserialize(decoder: Decoder): SortPart<T> {
        TODO("Not yet implemented")
    }
}