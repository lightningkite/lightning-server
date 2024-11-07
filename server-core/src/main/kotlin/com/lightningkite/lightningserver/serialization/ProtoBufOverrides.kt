package com.lightningkite.lightningserver.serialization

import com.lightningkite.UUID
import com.lightningkite.lightningserver.typed.uncontextualize
import com.lightningkite.serialization.DurationMsSerializer
import com.lightningkite.serialization.listElement
import com.lightningkite.serialization.mapKeyElement
import com.lightningkite.serialization.mapValueElement
import kotlinx.datetime.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.protobuf.ProtoIntegerType
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoType
import kotlin.time.Duration

val ProtoBufOverrides = SerializersModule {
    contextual(Duration::class, DurationMsSerializer)
    contextual(UUID::class, UUIDByteArraySerializer)
    contextual(Instant::class, InstantLongSerializer)
}

object UUIDByteArraySerializer : KSerializer<UUID> {
    val defer = ByteArraySerializer()
    override val descriptor: SerialDescriptor = SerialDescriptor("com.lightningkite.UUID", defer.descriptor)

    override fun deserialize(decoder: Decoder): UUID {
        return UUID.parse(decoder.decodeSerializableValue(defer))
    }

    override fun serialize(encoder: Encoder, value: UUID) {
        encoder.encodeSerializableValue(defer, value.toBytes())
    }
}

//class ProtobufSchema(val module: SerializersModule, val packageName: String) {
//    val aliases = HashMap<String, PAlias>()
//    val messages = HashMap<String, PMessage>()
//    val enums = HashMap<String, PEnum>()
//
//    data class PMessage(
//        val fields: List<PField>
//    )
//
//    data class PAlias(
//        val target: String,
//    )
//
//    data class PField(
//        val name: String,
//        val type: PType,
//        val optional: Boolean,
//        val number: Int,
//    )
//
//    data class PType(
//        val name: String,
//        val repeated: Boolean = false,
//        val nullable: Boolean = false,
//    )
//
//    data class PEnum(
//        val entries: List<Pair<String, Int>>
//    )
//
//    val typesHandled = HashSet<String>()
//
//    fun KSerializer<*>.protobufType(annotations: List<Annotation> = listOf()): PType {
//        val serializer = this
//        return when(serializer.descriptor.kind) {
//            SerialKind.CONTEXTUAL -> (Serialization.json.serializersModule.getContextual(
//                serializer.descriptor.capturedKClass ?: throw IllegalStateException("No captured KClass found for ${serializer.descriptor}")
//            ) ?: throw IllegalStateException("No contextual serializer found for ${serializer.descriptor.capturedKClass!!.qualifiedName}")).protobufType(annotations)
//            PrimitiveKind.BOOLEAN -> PType("bool", nullable = serializer.descriptor.isNullable)
//            PrimitiveKind.BYTE,
//            PrimitiveKind.CHAR,
//            PrimitiveKind.SHORT,
//            PrimitiveKind.INT -> {
//                val integerType = annotations.filterIsInstance<ProtoType>().firstOrNull()?.type ?: ProtoIntegerType.DEFAULT
//                when (integerType) {
//                    ProtoIntegerType.DEFAULT -> PType("int32", nullable = serializer.descriptor.isNullable)
//                    ProtoIntegerType.SIGNED -> PType("sint32", nullable = serializer.descriptor.isNullable)
//                    ProtoIntegerType.FIXED -> PType("fixed32", nullable = serializer.descriptor.isNullable)
//                }
//            }
//            PrimitiveKind.LONG -> {
//                val integerType = annotations.filterIsInstance<ProtoType>().firstOrNull()?.type ?: ProtoIntegerType.DEFAULT
//                when (integerType) {
//                    ProtoIntegerType.DEFAULT -> PType("int64", nullable = serializer.descriptor.isNullable)
//                    ProtoIntegerType.SIGNED -> PType("sint64", nullable = serializer.descriptor.isNullable)
//                    ProtoIntegerType.FIXED -> PType("fixed64", nullable = serializer.descriptor.isNullable)
//                }
//            }
//            PrimitiveKind.FLOAT -> PType("float", nullable = serializer.descriptor.isNullable)
//            PrimitiveKind.DOUBLE -> PType("double", nullable = serializer.descriptor.isNullable)
//            PrimitiveKind.STRING -> PType("string", nullable = serializer.descriptor.isNullable)
//            StructureKind.LIST -> {
//                val element = serializer.listElement()!!
//                is(element.descriptor.kind == PrimitiveKind.BYTE) {
//                    PType("bytes", nullable = serializer.descriptor.isNullable)
//                } else {
//                    element.protobufType(annotations).copy(repeated = true)
//                }
//                serializer.listElement()?.let { this += it }
//            }
//            StructureKind.MAP -> {
//                serializer.mapKeyElement()?.let { this += it }
//                serializer.mapValueElement()?.let { this += it }
//            }
//            StructureKind.OBJECT -> {}
//            StructureKind.CLASS -> {
//                val name = descriptor.serialName.corrected()
//                if(!typesHandled.add(name)) return name
//                messages[name] = PEnum((0..<serializer.descriptor.elementsCount).map { index ->
//                    val name = serializer.descriptor.getElementName(index).corrected()
//                    name to (serializer.descriptor.getElementAnnotations(index).filterIsInstance<ProtoNumber>().singleOrNull()?.number ?: index)
//                })
//                name
//            }
//            SerialKind.ENUM -> {
//                val name = descriptor.serialName.corrected()
//                if(!typesHandled.add(name)) return name
//                enums[name] = PEnum((0..<serializer.descriptor.elementsCount).map { index ->
//                    val name = serializer.descriptor.getElementName(index).corrected()
//                    name to (serializer.descriptor.getElementAnnotations(index).filterIsInstance<ProtoNumber>().singleOrNull()?.number ?: index)
//                })
//                name
//            }
//            PolymorphicKind.SEALED -> throw NotImplementedError()
//            PolymorphicKind.OPEN -> throw NotImplementedError()
//        }
//    }
//
//    fun String.corrected(): String {
//        val validChars = filter { it.isLetterOrDigit() || it == '_' }
//        if (validChars.isEmpty()) throw IllegalArgumentException("Can not correct name '$this' for ProtoBuf schema")
//        if (validChars[0] == '_') {
//            if (validChars.length == 1)
//                throw IllegalArgumentException("Can not correct name '$this' for ProtoBuf schema")
//            return validChars.drop(1)
//        }
//    }
//}
