package com.lightningkite.lightningdb

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind

/**
 * Used for database implementations.
 * An index that is needed for this model.
 */
data class NeededIndex(
    val fields: List<String>,
    val unique: Boolean = false,
    val name: String? = null,
)

/**
 * Used for database implementations.
 * Gives a list of needed indexes for the model.
 */
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

                is UniqueSetJankPatch -> it.fields.joinToString(",").split(",:,").forEach {
                    out.add(
                        NeededIndex(
                            fields = it.split(',').map { prefix + it },
                            unique = true,
                            name = null
                        )
                    )
                }

                is IndexSetJankPatch -> it.fields.joinToString(",").split(",:,").forEach {
                    out.add(
                        NeededIndex(
                            fields = it.split(',').map { prefix + it },
                            unique = false,
                            name = null
                        )
                    )
                }

                is NamedUniqueSetJankPatch -> it.fields.joinToString(",").split(",:,").forEachIndexed { index, part ->
                    out.add(
                        NeededIndex(
                            fields = part.split(',').map { prefix + it },
                            unique = true,
                            name = it.indexNames.split(':')[index]
                        )
                    )
                }

                is NamedIndexSetJankPatch -> it.fields.joinToString(",").split(",:,").forEachIndexed { index, part ->
                    out.add(
                        NeededIndex(
                            fields = part.split(',').map { prefix + it },
                            unique = false,
                            name = it.indexNames.split(':')[index]
                        )
                    )
                }
            }
        }
        (0 until descriptor.elementsCount).forEach { index ->
            val sub = descriptor.getElementDescriptor(index)
//            if (sub.kind == StructureKind.CLASS) handleDescriptor(sub, descriptor.getElementName(index) + ".")
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