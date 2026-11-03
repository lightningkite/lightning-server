package com.lightningkite.lightningserver.typed.denormalize

import com.lightningkite.services.Untested
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.optics.Field
import com.lightningkite.services.database.optics.Optic

// TODO: Tests needed
@Untested
internal fun <K, V> Modification<K>.vet(path: Optic<K, V>, onModification: (Modification<V>) -> Unit) =
    @Suppress("UNCHECKED_CAST")
    vet(path.fields as List<Field<Any?, *>>, onModification as (Modification<*>) -> Unit)

private fun Modification<*>.vet(path: List<Field<Any?, *>>, onModification: (Modification<*>) -> Unit) {
    when (this) {
        is Modification.Assign -> {
            var value = this.value
            for (key in path) {
                value = key.get(value)
            }
            onModification(Modification.Assign(value))
        }

        is Modification.Chain -> modifications.forEach { it.vet(path, onModification) }

        is Modification.OnField<*, *> ->
            if (this.key == path.firstOrNull()) {
                if (path.size == 1) onModification(this.modification)
                else this.modification.vet(
                    path.subList(1, path.size),
                    onModification
                )
            }

        is Modification.IfNotNull -> this.modification.vet(path, onModification)
        else -> {}
    }
}
