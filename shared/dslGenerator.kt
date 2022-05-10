import java.io.File

data class ConditionType(
    val name: String,
    val field: String,
    val fieldType: String,
    val serializer: String,
    val constraint: String = "",
    val useGeneric: Boolean = true
)

data class ModificationType(
    val name: String,
    val field: String,
    val fieldType: String,
    val serializer: String,
    val constraint: String = "",
    val useGeneric: Boolean = true
)

val conditionTypes =listOf(
    ConditionType("And", "conditions", "List<Condition<T>>", "ListSerializer(Condition.serializer(inner))"),
    ConditionType("Or", "conditions", "List<Condition<T>>", "ListSerializer(Condition.serializer(inner))"),
    ConditionType("Not", "condition", "Condition<T>", "Condition.serializer(inner)"),
    ConditionType("Equal", "value", "T", "inner", constraint = ": IsEquatable"),
    ConditionType("NotEqual", "value", "T", "inner", constraint = ": IsEquatable"),
    ConditionType("Inside", "values", "T", "ListSerializer(inner)", constraint = ": IsEquatable"),
    ConditionType("NotInside", "values", "T", "ListSerializer(inner)", constraint = ": IsEquatable"),
    ConditionType("GreaterThan", "value", "T", "inner", constraint = ": Comparable<T>"),
    ConditionType("LessThan", "value", "T", "inner", constraint = ": Comparable<T>"),
    ConditionType("GreaterThanOrEqual", "value", "T", "inner", constraint = ": Comparable<T>"),
    ConditionType("LessThanOrEqual", "value", "T", "inner", constraint = ": Comparable<T>"),
    ConditionType("IntBitsClear", "mask", "Int", "serializer<Int>()", useGeneric = false, constraint = "Int"),
    ConditionType("IntBitsSet", "mask", "Int", "serializer<Int>()", useGeneric = false, constraint = "Int"),
    ConditionType("IntBitsAnyClear", "mask", "Int", "serializer<Int>()", useGeneric = false, constraint = "Int"),
    ConditionType("IntBitsAnySet", "mask", "Int", "serializer<Int>()", useGeneric = false, constraint = "Int"),
    ConditionType("LongBitsClear", "mask", "Long", "serializer<Long>()", useGeneric = false, constraint = "Long"),
    ConditionType("LongBitsSet", "mask", "Long", "serializer<Long>()", useGeneric = false, constraint = "Long"),
    ConditionType("LongBitsAnyClear", "mask", "Long", "serializer<Long>()", useGeneric = false, constraint = "Long"),
    ConditionType("LongBitsAnySet", "mask", "Long", "serializer<Long>()", useGeneric = false, constraint = "Long"),
    ConditionType("AllElements", "condition", "Condition<T>", "Condition.serializer(inner)"),
    ConditionType("AnyElements", "condition", "Condition<T>", "Condition.serializer(inner)"),
    ConditionType("SizesEquals", "count", "Int", "serializer<Int>()"),
    ConditionType("IfNotNull", "condition", "T", "Condition.serializer(inner)")
)

val modificationTypes = listOf(
    ModificationType("Chain", "modifications", "List<Modification<T>>","ListSerializer(Modification.serializer(inner))"),
    ModificationType("IfNotNull", "modification", "Modification<T>", "Modification.serializer(inner)"),
    ModificationType("Assign", "value", "T", "inner"),
    ModificationType("CoerceAtMost", "value", "T", "inner", constraint = ": Comparable<T>"),
    ModificationType("CoerceAtLeast", "value", "T", "inner", constraint = ": Comparable<T>"),
    ModificationType("Increment", "by", "T", "inner", constraint = ": Number"),
    ModificationType("Multiply", "by", "T", "inner", constraint = ": Number"),
    ModificationType("AppendString", "value", "String", "serializer<String>()", useGeneric = false, constraint = "String"),
    ModificationType("AppendList", "items", "List<T>", "ListSerializer(inner)"),
    ModificationType("AppendSet", "items", "Set<T>", "ListSerializer(inner)"),
    ModificationType("Remove", "condition", "Condition<T>", "Condition.serializer(inner)"),
    ModificationType("RemoveInstances", "items", "List<T>", "ListSerializer(inner)"),
    ModificationType("Combine", "map", "Map<String, T>", "MapSerializer(serializer<String>(), inner)"),
    ModificationType("ModifyByKey", "map", "Map<String, Modification<T>>", "MapSerializer(serializer<String>(), Modification.serializer(inner))"),
    ModificationType("RemoveKeys", "fields", "Set<String>", "SetSerializer(serializer<String>())")
)

fun main() {
    generateModificationDsl()
}

fun generateConditionDsl() {
    for (entry in conditionTypes) {
        if(entry.useGeneric) {
            println(entry.run {
                """
                    infix fun <K, T${constraint}> PropChain<K, T>.$name($field: $fieldType) = mapCondition(Condition.$name($field))
                """.trimIndent()
            })
        } else {
            println(entry.run {
                """
                    infix fun <K> PropChain<K, $constraint>.$name($field: $fieldType) = mapCondition(Condition.$name($field))
                """.trimIndent()
            })
        }
    }
}

fun generateModificationDsl() {
    for (entry in modificationTypes) {
        if(entry.useGeneric) {
            println(entry.run {
                """
                    infix fun <K, T${constraint}> PropChain<K, T>.$name($field: $fieldType) = mapModification(Modification.$name($field))
                """.trimIndent()
            })
        } else {
            println(entry.run {
                """
                    infix fun <K> PropChain<K, $constraint>.$name($field: $fieldType) = mapModification(Modification.$name($field))
                """.trimIndent()
            })
        }
    }
}

fun generateConditionSerializers() {
    for (entry in conditionTypes) {
        if(entry.useGeneric) {
            println(entry.run {
                """
                    class Condition${name}Serializer<T$constraint>(val inner: KSerializer<T>) : KSerializer<Condition.${name}<T>> {
                        val to by lazy { $serializer }
                        override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("$name") { to.descriptor }
                        override fun deserialize(decoder: Decoder): Condition.${name}<T> = Condition.${name}(decoder.decodeSerializableValue(to))
                        override fun serialize(encoder: Encoder, value: Condition.${name}<T>) = encoder.encodeSerializableValue(to, value.${field})
                    }
                """.trimIndent()
            })
        } else {
            println(entry.run {
                """
                    object Condition${name}Serializer : KSerializer<Condition.${name}> {
                        val to by lazy { $serializer }
                        override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("$name") { to.descriptor }
                        override fun deserialize(decoder: Decoder): Condition.${name} = Condition.${name}(decoder.decodeSerializableValue(to))
                        override fun serialize(encoder: Encoder, value: Condition.${name}) = encoder.encodeSerializableValue(to, value.${field})
                    }
                """.trimIndent()
            })
        }
    }
}

fun generateModificationSerializers() {
    for (entry in modificationTypes) {
        if(entry.useGeneric) {
            println(entry.run {
                """
                    class Modification${name}Serializer<T$constraint>(val inner: KSerializer<T>) : KSerializer<Modification.${name}<T>> {
                        val to by lazy { $serializer }
                        override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("$name") { to.descriptor }
                        override fun deserialize(decoder: Decoder): Modification.${name}<T> = Modification.${name}(decoder.decodeSerializableValue(to))
                        override fun serialize(encoder: Encoder, value: Modification.${name}<T>) = encoder.encodeSerializableValue(to, value.${field})
                    }
                """.trimIndent()
            })
        } else {
            println(entry.run {
                """
                    object Modification${name}Serializer : KSerializer<Modification.${name}> {
                        val to by lazy { $serializer }
                        override val descriptor: SerialDescriptor = LazyRenamedSerialDescriptor("$name") { to.descriptor }
                        override fun deserialize(decoder: Decoder): Modification.${name} = Modification.${name}(decoder.decodeSerializableValue(to))
                        override fun serialize(encoder: Encoder, value: Modification.${name}) = encoder.encodeSerializableValue(to, value.${field})
                    }
                """.trimIndent()
            })
        }
    }
}