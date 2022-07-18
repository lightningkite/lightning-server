package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.jsonschema.encodeToSchema
import com.lightningkite.lightningserver.jsonschema.internal.createJsonSchema
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningdb.ServerFile
import com.lightningkite.lightningserver.serialization.serializerOrContextual
import io.ktor.util.*
import kotlinx.html.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.getContextualDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.time.Instant
import kotlin.reflect.KType
import kotlin.reflect.typeOf


fun HEAD.includeFormScript() {
    script { src = "https://cdn.jsdelivr.net/npm/@json-editor/json-editor@latest/dist/jsoneditor.min.js" }

    link(
        href = "https://cdn.jsdelivr.net/npm/flatpickr@4.6.3/dist/flatpickr.min.css",
        rel = "stylesheet"
    ) {
        attributes["crossorigin"] = "anonymous"
    }
    script {
        src = "https://cdn.jsdelivr.net/npm/flatpickr@4.6.3/dist/flatpickr.min.js"
        integrity = "sha256-/irFIZmSo2CKXJ4rxHWfrI+yGJuI16Z005X/bENdpTY="
        attributes["crossorigin"] = "anonymous"
    }
    unsafe {
        +"""
    
    <script src="https://cdn.jsdelivr.net/simplemde/latest/simplemde.min.js"></script>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/simplemde/latest/simplemde.min.css">
    <link rel="stylesheet" href="https://maxcdn.bootstrapcdn.com/font-awesome/latest/css/font-awesome.min.css">

    <link rel="stylesheet" href="//cdnjs.cloudflare.com/ajax/libs/jodit/3.17.1/jodit.min.css"/>
    <script src="//cdnjs.cloudflare.com/ajax/libs/jodit/3.17.1/jodit.min.js"></script>    
        """.trimIndent()
    }
}

@OptIn(InternalAPI::class)
inline fun <reified T> FORM.insideHtmlForm(
    title: String,
    jsEditorName: String,
    defaultValue: T? = null,
    collapsed: Boolean = false,
): Unit = insideHtmlForm(title, jsEditorName, serializerOrContextual(), defaultValue, collapsed)
fun <T> FORM.insideHtmlForm(
    title: String,
    jsEditorName: String,
    serializer: KSerializer<T>,
    defaultValue: T? = null,
    collapsed: Boolean = false,
): Unit {
    this.encType = FormEncType.multipartFormData
    input(InputType.hidden, name = "__json") {
        id = "$jsEditorName-input"
    }
    jsForm(
        title = title,
        jsEditorName = jsEditorName,
        serializer = serializer,
        defaultValue = defaultValue,
        collapsed = collapsed
    )
    script {
        unsafe {
            raw(
                """
                $jsEditorName.on('change', function () {
                    document.querySelector('#$jsEditorName-input').value = JSON.stringify(editor.getValue())
                })
                """.trimIndent()
            )
        }
    }
    div {
        classes = setOf("je-object__container")
        div {
            attributes.set("data-theme", "html")
            classes = setOf("je-indented-panel")
            serializer.descriptor.fileFieldNames().forEach {
                div {
                    classes = setOf("row")
                    label {
                        classes = setOf("je-form-input-label")
                        +"Upload "
                        +it.replace(".", " ")
                        input(InputType.file) {
                            classes = setOf("row")
                            this.name = it
                        }
                    }
                }
            }
        }
    }
}

internal fun SerialDescriptor.fileFieldNames(visited: MutableSet<SerialDescriptor> = mutableSetOf()): List<String> {
    if(!visited.add(this)) return listOf()
    return (0 until elementsCount)
        .flatMap {
            val name = getElementName(it)
            var descriptor = getElementDescriptor(it)
            if (descriptor.kind == SerialKind.CONTEXTUAL) {
                descriptor = Serialization.module.getContextualDescriptor(descriptor)!!
            }
            descriptor.fileFieldNames(visited).map { "$name.$it" } + if (descriptor == Serialization.module.getContextual(
                    ServerFile::class
                )?.descriptor
            ) listOf(
                name
            ) else listOf()
        }
}

inline fun <reified T> FlowContent.jsForm(
    title: String,
    jsEditorName: String,
    defaultValue: T? = null,
    collapsed: Boolean = false,
) = jsForm(title, jsEditorName, Serialization.module.serializer<T>(), defaultValue, collapsed)

fun <T> FlowContent.jsForm(
    title: String,
    jsEditorName: String,
    serializer: KSerializer<T>,
    defaultValue: T? = null,
    collapsed: Boolean = false,
) {
    val id = "${jsEditorName}-slot"
    div { this.id = id }
    script {
        unsafe {
            +"""
                const ${jsEditorName} = new JSONEditor(document.getElementById('$id'), {
                    disable_properties: true,
                    form_name_root: "$title",
                    collapsed: $collapsed,
                    schema: ${Serialization.json.encodeToSchema(serializer)},
                    max_depth: 5
                });
                ${
                if (defaultValue != null) "${jsEditorName}.on('ready', () => ${jsEditorName}.setValue(${
                    Json(
                        Serialization.json
                    ) { encodeDefaults = true }.encodeToString(serializer, defaultValue)
                }))" else ""
            }
            """.trimIndent()
        }
    }
}

inline fun <reified T> FlowContent.display(
    title: String,
    jsEditorName: String,
    defaultValue: T? = null,
    collapsed: Boolean = false,
) = displayUntyped(title, jsEditorName, Serialization.module.serializer<T>(), defaultValue, collapsed)

fun <T> FlowContent.displayUntyped(
    title: String,
    jsEditorName: String,
    serializer: KSerializer<T>,
    defaultValue: T? = null,
    collapsed: Boolean = false,
) {
    val id = "${jsEditorName}-slot"
    div { this.id = id }
    script {
        unsafe {
            +"""
                const ${jsEditorName} = new JSONEditor(document.getElementById('$id'), {
                    disable_properties: true,
                    form_name_root: "$title",
                    collapsed: $collapsed,
                    schema: ${Serialization.json.encodeToSchema(serializer)}
                });
                ${jsEditorName}.disable()
                ${
                if (defaultValue != null) "${jsEditorName}.setValue(${
                    Json(
                        Serialization.json
                    ) { encodeDefaults = true }.encodeToString(
                        serializer, defaultValue
                    )
                })" else ""
            }
            """.trimIndent()
        }
    }
}