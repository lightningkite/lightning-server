package com.lightningkite.lightningserver.forms


import com.lightningkite.lightningserver.serialization.serializerOrContextual
import kotlinx.html.*
import kotlinx.html.dom.HTMLDOMBuilder
import kotlinx.html.dom.createHTMLDocument
import kotlinx.html.input
import kotlinx.html.stream.appendHTML
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import java.lang.IllegalArgumentException
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.starProjectedType

typealias HtmlRenderer<T> = FlowContent.(inputKey: String, value: T) -> Unit

class HtmlSerializer(val serializersModule: SerializersModule = EmptySerializersModule, val module: Module) {
    inline fun <reified T> render(value: T, into: FlowContent) = render(serializerOrContextual(), value, into)
    fun <T> render(serializer: SerializationStrategy<T>, value: T, into: FlowContent) {
        HtmlEncoder(into).encodeSerializableValue(serializer, value)
    }

    data class Module(
        val renderers: Map<SerialDescriptor, HtmlRenderer<*>>
    )

    inner class HtmlEncoder(val flow: FlowContent): AbstractEncoder() {
        override val serializersModule: SerializersModule
            get() = this@HtmlSerializer.serializersModule

        override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
            super.encodeSerializableValue(serializer, value)
        }

        override fun <T : Any> encodeNullableSerializableValue(serializer: SerializationStrategy<T>, value: T?) {
            super.encodeNullableSerializableValue(serializer, value)
        }

        override fun encodeNull() {
            throw SerializationException("'null' is not supported by default")
        }

        override fun encodeBoolean(value: Boolean): Unit = encodeValue(value)
        override fun encodeByte(value: Byte): Unit = encodeValue(value)
        override fun encodeShort(value: Short): Unit = encodeValue(value)
        override fun encodeInt(value: Int): Unit = encodeValue(value)
        override fun encodeLong(value: Long): Unit = encodeValue(value)
        override fun encodeFloat(value: Float): Unit = encodeValue(value)
        override fun encodeDouble(value: Double): Unit = encodeValue(value)
        override fun encodeChar(value: Char): Unit = encodeValue(value)
        override fun encodeString(value: String): Unit = encodeValue(value)
        override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int): Unit = encodeValue(index)
    }
}

//class HtmlEncoder(val appendable: Appendable, override val serializersModule: SerializersModule) : Encoder {
//    val consumer = appendable.appendHTML()
//    var path = ""
//    private inline fun withPathAddition(addition: String, action: FlowContent.(value: Any?) -> Unit) {
//        val oldPath = path
//        path += "_$addition"
//        action()
//        path = oldPath
//    }
//
//    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder = object : CompositeEncoder {
//        init {
//        }
//
//        override val serializersModule: SerializersModule
//            get() = this@HtmlEncoder.serializersModule
//
//        override fun encodeBooleanElement(descriptor: SerialDescriptor, index: Int, value: Boolean) {
//            val name = descriptor.getElementName(index)
//
//            consumer.div {
//                withPathAddition(name) {
//                    encodeBoolean(value)
//                    label { +name.humanize(); this.htmlFor = path }
//                }
//            }
//        }
//
//        override fun encodeByteElement(descriptor: SerialDescriptor, index: Int, value: Byte) {
//            val name = descriptor.getElementName(index)
//            consumer.div {
//                withPathAddition(name) {
//                    label { +name.humanize(); this.htmlFor = path }
//                    encodeByte(value)
//                }
//            }
//        }
//
//        override fun encodeCharElement(descriptor: SerialDescriptor, index: Int, value: Char) {
//            val name = descriptor.getElementName(index)
//            consumer.div {
//                withPathAddition(name) {
//                    label { +name.humanize(); this.htmlFor = path }
//                    encodeChar(value)
//                }
//            }
//        }
//
//        override fun encodeDoubleElement(descriptor: SerialDescriptor, index: Int, value: Double) {
//            val name = descriptor.getElementName(index)
//            consumer.div {
//                withPathAddition(name) {
//                    label { +name.humanize(); this.htmlFor = path }
//                    encodeDouble(value)
//                }
//            }
//        }
//
//        override fun encodeFloatElement(descriptor: SerialDescriptor, index: Int, value: Float) {
//            val name = descriptor.getElementName(index)
//            consumer.div {
//                withPathAddition(name) {
//                    label { +name.humanize(); this.htmlFor = path }
//                    encodeFloat(value)
//                }
//            }
//        }
//
//        @ExperimentalSerializationApi
//        override fun encodeInlineElement(descriptor: SerialDescriptor, index: Int): Encoder {
//            return this@HtmlEncoder
//        }
//
//        override fun encodeIntElement(descriptor: SerialDescriptor, index: Int, value: Int) {
//            val name = descriptor.getElementName(index)
//            consumer.div {
//                withPathAddition(name) {
//                    label { +name.humanize(); this.htmlFor = path }
//                    encodeInt(value)
//                }
//            }
//        }
//
//        override fun encodeLongElement(descriptor: SerialDescriptor, index: Int, value: Long) {
//            val name = descriptor.getElementName(index)
//            consumer.div {
//                withPathAddition(name) {
//                    label { +name.humanize(); this.htmlFor = path }
//                    encodeLong(value)
//                }
//            }
//        }
//
//        @ExperimentalSerializationApi
//        override fun <T : Any> encodeNullableSerializableElement(
//            descriptor: SerialDescriptor,
//            index: Int,
//            serializer: SerializationStrategy<T>,
//            value: T?
//        ) {
//            val name = descriptor.getElementName(index)
//            consumer.div {
//                withPathAddition(name) {
//                    input(InputType.checkBox, name = path)
//                    label { +name.humanize(); this.htmlFor = path }
//                    encodeSerializableValue(serializer, value ?: serializer.constructDefault())
//                }
//            }
//        }
//
//        override fun <T> encodeSerializableElement(
//            descriptor: SerialDescriptor,
//            index: Int,
//            serializer: SerializationStrategy<T>,
//            value: T
//        ) {
//            val name = descriptor.getElementName(index)
//            consumer.div {
//                withPathAddition(name) {
//                    label { +name.humanize() }
//                    encodeSerializableValue(serializer, value)
//                }
//            }
//        }
//
//        override fun encodeShortElement(descriptor: SerialDescriptor, index: Int, value: Short) {
//            val name = descriptor.getElementName(index)
//            consumer.div {
//                withPathAddition(name) {
//                    label { +name.humanize(); this.htmlFor = path }
//                    encodeShort(value)
//                }
//            }
//        }
//
//        override fun encodeStringElement(descriptor: SerialDescriptor, index: Int, value: String) {
//            val name = descriptor.getElementName(index)
//            consumer.div {
//                withPathAddition(name) {
//                    label { +name.humanize(); this.htmlFor = path }
//                    encodeString(value)
//                }
//            }
//        }
//
//        override fun endStructure(descriptor: SerialDescriptor) {
//        }
//
//    }
//
//    override fun encodeBoolean(value: Boolean) {
//        consumer.input(InputType.checkBox, name = path) { this.value = value.toString() }
//    }
//
//    override fun encodeByte(value: Byte) {
//        consumer.input(InputType.number, name = path) {
//            this.value = value.toString()
//        }
//    }
//
//    override fun encodeChar(value: Char) {
//        consumer.input(InputType.text, name = path) { this.value = value.toString() }
//    }
//
//    override fun encodeDouble(value: Double) {
//        consumer.input(InputType.number, name = path) {
//            step = "0.01"
//            this.value = value.toString()
//        }
//    }
//
//    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
//        consumer.select {
//            this.name = path
//            enumDescriptor.elementNames.forEachIndexed { i, it ->
//                option {
//                    this.value = it
//                    this.label = it
//                    this.selected = index == i
//                }
//            }
//        }
//    }
//
//    override fun encodeFloat(value: Float) {
//        consumer.input(InputType.number, name = path) {
//            step = "0.01"
//            this.value = value.toString()
//        }
//    }
//
//    @ExperimentalSerializationApi
//    override fun encodeInline(inlineDescriptor: SerialDescriptor): Encoder = this
//
//    override fun encodeInt(value: Int) {
//        consumer.input(InputType.number, name = path) {
//            this.value = value.toString()
//        }
//    }
//
//    override fun encodeLong(value: Long) {
//        consumer.input(InputType.number, name = path) {
//            this.value = value.toString()
//        }
//    }
//
//    @ExperimentalSerializationApi
//    override fun encodeNull() {
//    }
//
//    override fun encodeShort(value: Short) {
//        consumer.input(InputType.number, name = path) {
//            this.value = value.toString()
//        }
//    }
//
//    override fun encodeString(value: String) {
//        consumer.input(InputType.text, name = path) {
//            this.value = value
//        }
//    }
//}

fun String.humanize(): String = this
