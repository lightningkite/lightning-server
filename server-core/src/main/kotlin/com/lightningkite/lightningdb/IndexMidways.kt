package com.lightningkite.lightningdb

import kotlinx.coroutines.launch
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind

data class NeededIndex(
    val fields: List<String>,
    val unique: Boolean = false,
    val name: String? = null,
)

fun SerialDescriptor.indexes(): Set<NeededIndex> {
    val seen = HashSet<SerialDescriptor>()
    val out = HashSet<NeededIndex>()
    fun handleDescriptor(descriptor: SerialDescriptor, prefix: String = "") {
        if (!seen.add(descriptor)) return
        descriptor.annotations.forEach {
            when (it) {
                is UniqueSet -> out.add(NeededIndex(fields = it.fields.map { prefix + it }, unique = true, name = null))
                is IndexSet -> out.add(NeededIndex(fields = it.fields.map { prefix + it }, unique = false, name = null))
                is NamedUniqueSet -> out.add(
                    NeededIndex(
                        fields = it.fields.map { prefix + it },
                        unique = true,
                        name = it.indexName
                    )
                )

                is NamedIndexSet -> out.add(
                    NeededIndex(
                        fields = it.fields.map { prefix + it },
                        unique = false,
                        name = it.indexName
                    )
                )
            }
        }
        (0 until descriptor.elementsCount).forEach { index ->
            val sub = descriptor.getElementDescriptor(index)
            if (sub.kind == StructureKind.CLASS) handleDescriptor(sub, descriptor.getElementName(index) + ".")
            descriptor.getElementAnnotations(index).forEach {
                when (it) {
                    is NamedIndex -> out.add(
                        NeededIndex(
                            fields = listOf(prefix + descriptor.getElementName(index)),
                            unique = false,
                            name = it.indexName
                        )
                    )

                    is Index -> out.add(
                        NeededIndex(
                            fields = listOf(prefix + descriptor.getElementName(index)),
                            unique = false,
                            name = null
                        )
                    )

                    is NamedUnique -> out.add(
                        NeededIndex(
                            fields = listOf(prefix + descriptor.getElementName(index)),
                            unique = true,
                            name = it.indexName
                        )
                    )

                    is Unique -> out.add(
                        NeededIndex(
                            fields = listOf(prefix + descriptor.getElementName(index)),
                            unique = true,
                            name = null
                        )
                    )
                }
            }
        }
    }
    handleDescriptor(this, "")
    return out
}