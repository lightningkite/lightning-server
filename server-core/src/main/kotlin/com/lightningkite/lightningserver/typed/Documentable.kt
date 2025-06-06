@file:OptIn(InternalSerializationApi::class, InternalSerializationApi::class)

package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningdb.*
import com.lightningkite.serialization.*
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.Http
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.websocket.WebSockets
import com.lightningkite.serialization.*
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.capturedKClass
import kotlinx.serialization.internal.GeneratedSerializer
import kotlinx.serialization.modules.SerializersModule

class DocumentableException(message:String? = null, cause: Throwable? = null): Exception(message, cause)

interface Documentable {
    val path: TypedServerPath
    val summary: String
    val description: String
    val authOptions: AuthOptions<*>
    val belongsToInterface: InterfaceInfo?

    class InterfaceInfo(val path: ServerPath, val name: String, val subtypes: List<KSerializer<*>>, val imports: Set<String>? = null) {
        override fun toString(): String = "$name at $path"
    }

    companion object {
        val endpoints get() = Http.endpoints.values
            .asSequence()
            .filterIsInstance<ApiEndpoint<*, *, *, *>>()

        val interfaces
            get() = Http.endpoints.values
                .asSequence()
                .filterIsInstance<ApiEndpoint<*, *, *, *>>()
                .mapNotNull { it.belongsToInterface }
                .distinct()

        val websockets get() = WebSockets.handlers.values
            .asSequence()
            .filterIsInstance<ApiWebsocket<*, *, *, *, *>>()

        val all get() = endpoints + websockets
        val usedTypes: Collection<KSerializer<*>>
            get() {
                val seen: HashSet<SerialDescriptor> = HashSet()
                fun onAllTypes(at: KSerializer<*>, action: (KSerializer<*>) -> Unit) {
                    val real = (at.nullElement() ?: at).uncontextualize()
                    if (!seen.add(real.descriptor)) return
                    action(real)
                    real.subAndChildSerializers().forEach { onAllTypes(it, action) }
                }

                val types = HashMap<String, KSerializer<*>>()

                endpoints.forEach { endpoint ->
                    try {
                        onAllTypes(endpoint.inputType) { types[it.descriptor.serialName.substringBefore('<')] = it }
                        onAllTypes(endpoint.outputType) { types[it.descriptor.serialName.substringBefore('<')] = it }
                    } catch (e: Exception) {
                        throw DocumentableException("Failed to generate typing for ${endpoint.path}", e)
                    }
                }

                websockets.forEach { endpoint ->
                    try {
                        onAllTypes(endpoint.inputType) { types[it.descriptor.serialName.substringBefore('<')] = it }
                        onAllTypes(endpoint.outputType) { types[it.descriptor.serialName.substringBefore('<')] = it }
                    } catch (e: Exception) {
                        throw DocumentableException("Failed to generate typing for ${endpoint.path}", e)
                    }
                }

                return types.values
            }
    }
}

val ServerPath.docGroup: String? get() = generateSequence(this) { it.parent }.mapNotNull { it.docName }.firstOrNull()
val Documentable.docGroup: String?
    get() = generateSequence(path.path) { it.parent }.mapNotNull { it.docName }.firstOrNull()
val Documentable.docGroupIdentifier: String?
    get() = docGroup
        ?.replace(Regex("""[^0-9a-zA-Z]+(?<following>.)?""")) { match ->
            match.groups["following"]?.value?.uppercase() ?: ""
        }
        ?.replaceFirstChar { it.lowercase() }
val Documentable.functionName: String
    get() = summary
        .replace(Regex("""[^0-9a-zA-Z]+(?<following>.)?""")) { match ->
            match.groups["following"]?.value?.uppercase() ?: ""
        }
        .replaceFirstChar { it.lowercase() }

internal fun KSerializer<*>.subSerializers(): Array<KSerializer<*>> = nullElement()?.let { arrayOf(it) }
    ?: listElement()?.let { arrayOf(it) }
    ?: mapValueElement()?.let { arrayOf(it) }
    ?: (this as? GeneratedSerializer<*>)?.typeParametersSerializers()
    ?: (this as? ConditionSerializer<*>)?.inner?.let { arrayOf(it) }
    ?: (this as? ModificationSerializer<*>)?.inner?.let { arrayOf(it) }
    ?: (this as? PartialSerializer<*>)?.source?.let { arrayOf(it) }
    ?: (this as? SortPartSerializer<*>)?.inner?.let { arrayOf(it) }
    ?: (this as? DataClassPathSerializer<*>)?.inner?.let { arrayOf(it) }
    ?: arrayOf()

internal fun KSerializer<*>.subAndChildSerializers(): Array<KSerializer<*>> = nullElement()?.let { arrayOf(it) }
    ?: serializableProperties?.map { it.serializer }?.toTypedArray()
    ?: listElement()?.let { arrayOf(it) }
    ?: mapValueElement()?.let { arrayOf(it) }
    ?: (this as? GeneratedSerializer<*>)?.run { childSerializers() + typeParametersSerializers() }
    ?: (this as? ConditionSerializer<*>)?.inner?.let { arrayOf(it) }
    ?: (this as? ModificationSerializer<*>)?.inner?.let { arrayOf(it) }
    ?: (this as? PartialSerializer<*>)?.source?.let { arrayOf(it) }
    ?: (this as? SortPartSerializer<*>)?.inner?.let { arrayOf(it) }
    ?: (this as? DataClassPathSerializer<*>)?.inner?.let { arrayOf(it) }
    ?: arrayOf()

internal fun KSerializer<*>.uncontextualize(module: SerializersModule = Serialization.json.serializersModule): KSerializer<*> {
    return if (this.descriptor.kind == SerialKind.CONTEXTUAL) {
        module.getContextual(
            descriptor.capturedKClass ?: throw IllegalStateException("No captured KClass found for $descriptor")
        )
            ?: throw IllegalStateException("No contextual serializer found for ${descriptor.capturedKClass!!.qualifiedName}")
    } else this
}
