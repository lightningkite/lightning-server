package com.lightningkite.lightningserver.scim

import com.lightningkite.IsRawString
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
import com.lightningkite.serialization.nullElement
import com.lightningkite.serialization.serializableProperties
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.StringFormat
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
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
        return fromString(string)
    }

    private fun ScimFilterParser.AttrPathContext.handleNn(makeCondition: (KSerializer<Any?>) -> Condition<Any?>): Condition<T> {
        return handle { ser ->
            if (ser.descriptor.isNullable) {
                Condition.IfNotNull(makeCondition(ser.nullElement()!! as KSerializer<Any?>))
            } else {
                makeCondition(ser)
            }
        }
    }

    private fun ScimFilterParser.AttrPathContext.handle(autoVal: Boolean = true, makeCondition: (KSerializer<Any?>) -> Condition<Any?>): Condition<T> {

        val mainName = ATTRNAME(0)!!.text?.lowercase()
//        if(mainName == "value") return makeCondition(subtype as KSerializer<Any?>) as Condition<T>
        val props = subtype.serializableProperties
            ?: throw IllegalStateException("serializableProperties not available on ${subtype.descriptor.serialName}")
        val schema = SCHEMA()?.text?.lowercase()
        val mainNameProperty = props.find { it.name.lowercase() == mainName }
        val subName = ATTRNAME(1)?.text?.lowercase() ?: if(autoVal && (mainNameProperty?.serializer?.descriptor?.kind == StructureKind.CLASS || (mainNameProperty?.serializer?.descriptor?.kind == StructureKind.LIST &&
                    mainNameProperty?.serializer?.innerElement()?.descriptor?.kind == StructureKind.CLASS))) "value" else null

        return when {
            mainName == "meta" && subName == "created" -> {
                val p =
                    props.find { it.name == "createdAt" } ?: throw SerializationException("No property createdAt found")
                Condition.OnField(p, makeCondition(p.serializer as KSerializer<Any?>))
            }

            mainName == "meta" && subName == "lastmodified" -> {
                val p = props.find { it.name == "modifiedAt" }
                    ?: throw SerializationException("No property modifiedAt found")
                Condition.OnField(p, makeCondition(p.serializer as KSerializer<Any?>))
            }

            subName == null -> {
                val p = mainNameProperty
                    ?: throw SerializationException("No property ${mainName} found")
                Condition.OnField(p, makeCondition(p.serializer as KSerializer<Any?>))
            }

            else -> {
                val p1 = mainNameProperty
                    ?: throw SerializationException("No property ${mainName} found")
                val listWrap = p1.serializer.descriptor.kind == StructureKind.LIST
                val subProps = (if(listWrap) p1.serializer.innerElement().serializableProperties else p1.serializer.serializableProperties)
                    ?: throw IllegalStateException("serializableProperties not available on ${p1.serializer.descriptor.serialName}")
                val p2 = subProps.find { it.name.lowercase() == subName }
                    ?: throw SerializationException("No property ${subName} found on ${mainName}")
                val innermost = makeCondition(p2.serializer as KSerializer<Any?>)
                val p2nn = Condition.OnField(p2, innermost) as Condition<Any?>
                val p2nnc = when(mainNameProperty.serializer.descriptor.serialName) {
                    ListSerializer(Int.serializer()).descriptor.serialName -> Condition.ListAnyElements<List<Any?>>(p2nn)
                    SetSerializer(Int.serializer()).descriptor.serialName -> Condition.SetAnyElements<Set<Any?>>(p2nn)
                    else -> p2nn
                } as Condition<Any?>
                val inner = if (p1.serializer.descriptor.isNullable)
                    Condition.IfNotNull(p2nnc) as Condition<Any?>
                else
                    p2nnc
                Condition.OnField(p1, inner)
            }
        }
    }

    private fun ScimFilterParser.AndExpContext.toCondition(): Condition<T> = Condition.And(
        filter()
            .map { it.toCondition() }
    )

    private fun ScimFilterParser.ValPathExpContext.toCondition(): Condition<T> = attrPath().handle(autoVal = false) { serializer ->
        val inner =
            with(ScimConditionSerializer(serializer.innerElement())) { filter().toCondition() } as Condition<Any?>
        when (serializer.descriptor.serialName) {
            ListSerializer(Int.serializer()).descriptor.serialName -> Condition.ListAnyElements<List<Any?>>(inner)
            SetSerializer(Int.serializer()).descriptor.serialName -> Condition.SetAnyElements<Set<Any?>>(inner)
            else -> throw IllegalArgumentException()
        } as Condition<Any?>
    }

    private fun ScimFilterParser.PresentExpContext.toCondition(): Condition<T> =
        attrPath().handle { serializer -> Condition.NotEqual(null) }

    @Suppress("UNCHECKED_CAST")
    private fun ScimFilterParser.OperatorExpContext.toCondition(): Condition<T> =
        when (val tokenType = this.COMPAREOPERATOR()!!.text.uppercase()) {
            "EQ" -> attrPath().handle { serializer ->
                Condition.Equal(
                    Serialization.json.decodeFromString(
                        serializer,
                        VALUE().text
                    )
                )
            }

            "NE" -> attrPath().handle { serializer ->
                Condition.NotEqual(
                    Serialization.json.decodeFromString(
                        serializer,
                        VALUE().text
                    )
                )
            }

            "CO" -> attrPath().handleNn { serializer ->
                when (serializer.descriptor.serialName.substringBefore('/')) {
                    ListSerializer(Int.serializer()).descriptor.serialName -> Condition.ListAnyElements<List<Any?>>(
                        Condition.Equal(Serialization.json.decodeFromString(serializer.innerElement(), VALUE().text))
                    )

                    SetSerializer(Int.serializer()).descriptor.serialName -> Condition.SetAnyElements<Set<Any?>>(
                        Condition.Equal(Serialization.json.decodeFromString(serializer.innerElement(), VALUE().text))
                    )

                    "kotlin.String" -> Condition.StringContains(
                        Serialization.json.decodeFromString(
                            String.serializer(),
                            VALUE().text
                        )
                    )

                    else -> Condition.RawStringContains<IsRawString>(
                        Serialization.json.decodeFromString(
                            String.serializer(),
                            VALUE().text
                        ), true
                    ) as Condition<Any?>
                } as Condition<Any?>
            }

            "SW" -> attrPath().handleNn { serializer ->
                val v = Serialization.json.decodeFromString(String.serializer(), VALUE().text)
                // TODO: There are edges here - case sensitivity should be controlled by the field in question, start/ends incorrect
                if (serializer.descriptor.serialName.substringBefore('/') == "kotlin.String") {
                    Condition.RegexMatches("^" + Regex.escape(v)) as Condition<Any?>
                } else {
                    Condition.RawStringContains<IsRawString>(v, true) as Condition<Any?>
                }
            }

            "EW" -> attrPath().handleNn { serializer ->
                val v = Serialization.json.decodeFromString(String.serializer(), VALUE().text)
                // TODO: There are edges here - case sensitivity should be controlled by the field in question, start/ends incorrect
                if (serializer.descriptor.serialName.substringBefore('/') == "kotlin.String") {
                    Condition.RegexMatches(Regex.escape(v) + "$") as Condition<Any?>
                } else {
                    Condition.RawStringContains<IsRawString>(v, true) as Condition<Any?>
                }
            }

            "GT" -> attrPath().handleNn { serializer ->
                val v = Serialization.json.decodeFromString(serializer, VALUE().text) as Comparable<Comparable<*>>
                Condition.GreaterThan(v) as Condition<Any?>
            }

            "GE" -> attrPath().handleNn { serializer ->
                val v = Serialization.json.decodeFromString(serializer, VALUE().text) as Comparable<Comparable<*>>
                Condition.GreaterThan(v) as Condition<Any?>
            }

            "LT" -> attrPath().handleNn { serializer ->
                val v = Serialization.json.decodeFromString(serializer, VALUE().text) as Comparable<Comparable<*>>
                Condition.GreaterThan(v) as Condition<Any?>
            }

            "LE" -> attrPath().handleNn { serializer ->
                val v = Serialization.json.decodeFromString(serializer, VALUE().text) as Comparable<Comparable<*>>
                Condition.GreaterThan(v) as Condition<Any?>
            }

            else -> throw IllegalArgumentException("Token type ${tokenType} not known")
        }

    private fun ScimFilterParser.BraceExpContext.toCondition(): Condition<T> = if (this.NOT() != null)
        Condition.Not(filter().toCondition())
    else
        filter().toCondition()

    private fun ScimFilterParser.OrExpContext.toCondition(): Condition<T> = Condition.Or(
        filter()
            .map { it.toCondition() }
    )

    private fun ScimFilterParser.FilterContext.toCondition(): Condition<T> {
        return when (val element = this) {
            is ScimFilterParser.AndExpContext -> element.toCondition()
            is ScimFilterParser.ValPathExpContext -> element.toCondition()
            is ScimFilterParser.PresentExpContext -> element.toCondition()
            is ScimFilterParser.OperatorExpContext -> element.toCondition()
            is ScimFilterParser.BraceExpContext -> element.toCondition()
            is ScimFilterParser.OrExpContext -> element.toCondition()
            else -> throw IllegalArgumentException("No filter found")
        }
    }

    fun fromString(string: String): Condition<T> {
        val str = StringCharStream(string)
        val lex = ScimFilterLexer(str)
        val stream = CommonTokenStream(lex)
        val parse = ScimFilterParser(stream)

        return parse.filter().toCondition()
    }

    fun fromStringUntilTermination(string: String, start: Int = 0): Pair<Condition<T>, Int> {
        val str = StringCharStream(string.substring(start))
        val lex = ScimFilterLexer(str)
        val stream = CommonTokenStream(lex)
        val parse = ScimFilterParser(stream)
        val f = parse.filter()
        return f.toCondition() to f.position!!.end.offset(string)
    }
}
