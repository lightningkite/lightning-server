@file:OptIn(InternalSerializationApi::class)

package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.auth.AuthInfo
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.Http
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.websocket.WebSockets
import io.ktor.server.routing.*
import kotlinx.html.*
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.capturedKClass
import kotlinx.serialization.internal.GeneratedSerializer
import kotlinx.serialization.serializer
import kotlin.reflect.KType

interface Documentable {
    val path: ServerPath
    val summary: String
    val description: String
    val authInfo: AuthInfo<*>

    companion object {
        val endpoints get() = Http.routes.values.asSequence().filterIsInstance<ApiEndpoint<*, *, *>>()
        val websockets get() = WebSockets.handlers.values.asSequence().filterIsInstance<ApiWebsocket<*, *, *>>()
        val all get() = endpoints + websockets
        val usedTypes: Collection<KSerializer<*>> get() {
                val seen: HashSet<String> = HashSet()
                fun onAllTypes(at: KSerializer<*>, action: (KSerializer<*>) -> Unit) {
                    val real = (at.nullElement() ?: at).uncontextualize()
                    if (!seen.add(real.descriptor.serialName.substringBefore('<'))) return
                    action(real)
                    real.subAndChildSerializers().forEach { onAllTypes(it, action) }
                }
                val types = HashMap<String, KSerializer<*>>()
                endpoints.flatMap {
                    sequenceOf(it.inputType, it.outputType)
                }.plus(websockets.flatMap {
                    sequenceOf(it.inputType, it.outputType)
                })
                    .forEach { onAllTypes(it) { types[it.descriptor.serialName.substringBefore('<')] = it } }
                return types.values
        }
    }
}
val Documentable.docGroup: String? get() = generateSequence(path) { it.parent }.mapNotNull { it.docName }.firstOrNull()
val Documentable.functionName: String get() = summary.split(' ').joinToString("") { it.replaceFirstChar { it.uppercase() } }.replaceFirstChar { it.lowercase() }

internal fun KSerializer<*>.subSerializers(): Array<KSerializer<*>> = listElement()?.let { arrayOf(it) }
    ?: mapValueElement()?.let { arrayOf(it) }
    ?: (this as? GeneratedSerializer<*>)?.typeParametersSerializers()
    ?: (this as? ConditionSerializer<*>)?.inner?.let { arrayOf(it) }
    ?: (this as? ModificationSerializer<*>)?.inner?.let { arrayOf(it) }
    ?: arrayOf()
internal fun KSerializer<*>.subAndChildSerializers(): Array<KSerializer<*>> = listElement()?.let { arrayOf(it) }
    ?: mapValueElement()?.let { arrayOf(it) }
    ?: (this as? GeneratedSerializer<*>)?.run { childSerializers() + typeParametersSerializers() }
    ?: (this as? ConditionSerializer<*>)?.inner?.let { arrayOf(it) }
    ?: (this as? ModificationSerializer<*>)?.inner?.let { arrayOf(it) }
    ?: arrayOf()
internal fun KSerializer<*>.uncontextualize(): KSerializer<*> = if (this.descriptor.kind == SerialKind.CONTEXTUAL)
    Serialization.json.serializersModule.getContextual(descriptor.capturedKClass!!)!!
else this