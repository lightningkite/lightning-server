package com.lightningkite.ktorbatteries.typed

import com.lightningkite.ktorbatteries.jsonschema.encodeToSchema
import com.lightningkite.ktorbatteries.serialization.Serialization
import kotlinx.html.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.reflect.KType
import kotlin.reflect.typeOf


fun HEAD.includeFormScript() {
    link(
        href="https://cdn.jsdelivr.net/npm/bootstrap@5.1.3/dist/css/bootstrap.min.css",
        rel = "stylesheet"
    ) {
        integrity = "sha384-1BmE4kWBq78iYhFldvKuhfTAU6auU8tT94WrHftjDbrCEXSU1oBoqyl2QvZ6jIW3"
        attributes["crossorigin"] = "anonymous"
    }
    script { src = "https://cdn.jsdelivr.net/npm/@json-editor/json-editor@latest/dist/jsoneditor.min.js" }
}

inline fun <reified T> FORM.insideHtmlForm(
    title: String,
    jsEditorName: String,
    defaultValue: T? = null,
    collapsed: Boolean = false,
) {
    input(InputType.hidden, name="__json") {
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
            raw("""
                $jsEditorName.on('change', function () {
                    document.querySelector('#$jsEditorName-input').value = JSON.stringify(editor.getValue())
                })
                """.trimIndent())
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
                    theme: 'bootstrap4',
                    schema: ${Serialization.json.encodeToSchema(Serialization.module.serializer(type))}
                });
                ${if(defaultValue != null) "${jsEditorName}.on('ready', () => ${jsEditorName}.setValue(${Json(Serialization.json) { encodeDefaults = true }.encodeToString(Serialization.module.serializer(type), defaultValue)}))" else "" }
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
                    theme: 'bootstrap4',
                    schema: ${Serialization.json.encodeToSchema(Serialization.module.serializer(type))}
                });
                ${jsEditorName}.disable()
                ${if(defaultValue != null) "${jsEditorName}.setValue(${Serialization.json.encodeToString(Serialization.module.serializer(type), defaultValue)})" else "" }
            """.trimIndent()
        }
    }
}