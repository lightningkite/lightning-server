package com.lightningkite.ktorbatteries.typed

import com.lightningkite.ktorbatteries.jsonschema.encodeToSchema
import com.lightningkite.ktorbatteries.serialization.Serialization
import kotlinx.html.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
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

inline fun <reified T> FORM.insideHtmlForm(
    title: String,
    jsEditorName: String,
    defaultValue: T? = null,
    collapsed: Boolean = false,
) {
    this.encType = FormEncType.multipartFormData
    input(InputType.hidden, name = "__json") {
        id = "$jsEditorName-input"
    }
    jsForm(
        title = title,
        jsEditorName = jsEditorName,
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
}

inline fun <reified T> FlowContent.jsForm(
    title: String,
    jsEditorName: String,
    defaultValue: T? = null,
    collapsed: Boolean = false,
) = jsFormUntyped(title, jsEditorName, typeOf<T>(), defaultValue, collapsed)

fun FlowContent.jsFormUntyped(
    title: String,
    jsEditorName: String,
    type: KType,
    defaultValue: Any? = null,
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
                    schema: ${Serialization.json.encodeToSchema(Serialization.module.serializer(type))}
                });
                ${
                if (defaultValue != null) "${jsEditorName}.on('ready', () => ${jsEditorName}.setValue(${
                    Json(
                        Serialization.json
                    ) { encodeDefaults = true }.encodeToString(Serialization.module.serializer(type), defaultValue)
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
) = displayUntyped(title, jsEditorName, typeOf<T>(), defaultValue, collapsed)

fun FlowContent.displayUntyped(
    title: String,
    jsEditorName: String,
    type: KType,
    defaultValue: Any? = null,
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
                    schema: ${Serialization.json.encodeToSchema(Serialization.module.serializer(type))}
                });
                ${jsEditorName}.disable()
                ${
                if (defaultValue != null) "${jsEditorName}.setValue(${
                    Serialization.json.encodeToString(
                        Serialization.module.serializer(
                            type
                        ), defaultValue
                    )
                })" else ""
            }
            """.trimIndent()
        }
    }
}