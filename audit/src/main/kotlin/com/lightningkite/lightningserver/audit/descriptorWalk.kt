@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.lightningserver.audit

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors

/** Whether this descriptor's model is marked [Audited]. */
public val SerialDescriptor.isAudited: Boolean get() = annotations.any { it is Audited }

/**
 * The serial name with any nullability marker removed.
 *
 * kotlinx wraps a nullable descriptor in a delegate that appends `?` to the serial name and changes
 * nothing else. Registry keys must be the same whether a model appears nullable or not.
 */
public val SerialDescriptor.auditSerialName: String get() = serialName.removeSuffix("?")

/**
 * Every [Audited] model reachable through this descriptor, keyed by [auditSerialName].
 *
 * Finds audited models wherever they are: bare, in a `List`, as map values, nested inside a wrapper,
 * or as a sealed subclass. Open polymorphism and contextual serializers are opaque here — their
 * concrete types are not known statically — which is why an audited model that arrives at runtime
 * with no registry entry fails the request rather than being logged as unknown.
 */
public fun SerialDescriptor.auditedModels(): Map<String, SerialDescriptor> {
    val found = LinkedHashMap<String, SerialDescriptor>()
    val visited = HashSet<SerialDescriptor>()

    fun walk(descriptor: SerialDescriptor) {
        if (!visited.add(descriptor)) return
        if (descriptor.isAudited) found[descriptor.auditSerialName] = descriptor
        descriptor.auditChildren().forEach(::walk)
    }

    walk(this)
    return found
}

/**
 * The dotted field paths of an audited model, in declaration order — the paths that get permanent
 * bit indices.
 *
 * The walk descends through structures that have no record of their own and stops at anything that
 * does:
 *
 * | Shape | Result |
 * |---|---|
 * | Property | a path, always |
 * | Nested non-audited class | the container's path, then one per field beneath it |
 * | Nested [Audited] model | nothing — it produces its own `DisclosureRecord` |
 * | List/Set | descends into the element, marked `[]` |
 * | Map | descends into the value, marked `{}` |
 * | Sealed class | descends into each subclass, marked `(SubclassSerialName)` |
 * | Open polymorphic, contextual, primitive | nothing beneath — there is no static structure |
 *
 * Keeping a path for the container as well as its leaves is what distinguishes "no address was
 * disclosed" from "an address was disclosed, all of whose fields held defaults".
 */
public fun SerialDescriptor.auditFieldPaths(): List<String> = FieldPathWalk().also { it.members(this, "") }.paths

/**
 * Mutual recursion between "list the members of this structure" and "descend into one member",
 * which local functions cannot express.
 */
private class FieldPathWalk {
    val paths = ArrayList<String>()

    /** Guards against a structure that contains itself; entries are released as the walk unwinds. */
    private val ancestry = HashSet<SerialDescriptor>()

    fun members(descriptor: SerialDescriptor, prefix: String) {
        for (index in 0 until descriptor.elementsCount) {
            val path = prefix + descriptor.getElementName(index)
            paths.add(path)
            descend(descriptor.getElementDescriptor(index), path)
        }
    }

    private fun descend(descriptor: SerialDescriptor, path: String) {
        // An audited model beneath an audited model is a separate record, not more bits on the parent.
        if (descriptor.isAudited) return
        if (!ancestry.add(descriptor)) return
        when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> members(descriptor, "$path.")
            StructureKind.LIST -> descend(descriptor.getElementDescriptor(0), "$path[]")
            StructureKind.MAP -> descend(descriptor.getElementDescriptor(1), "$path{}")
            PolymorphicKind.SEALED -> descriptor.auditChildren()
                .forEach { members(it, "$path(${it.auditSerialName}).") }

            else -> {}
        }
        ancestry.remove(descriptor)
    }
}

/**
 * The descriptors nested directly inside this one, or none for anything opaque.
 *
 * A sealed class is special: its subclasses hang off its second element rather than being elements
 * themselves.
 */
private fun SerialDescriptor.auditChildren(): List<SerialDescriptor> = when (kind) {
    is PrimitiveKind, SerialKind.ENUM, SerialKind.CONTEXTUAL, PolymorphicKind.OPEN -> emptyList()
    PolymorphicKind.SEALED -> getElementDescriptor(1).elementDescriptors.toList()
    else -> elementDescriptors.toList()
}
