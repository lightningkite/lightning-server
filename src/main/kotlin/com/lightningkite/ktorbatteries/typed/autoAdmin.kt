package com.lightningkite.ktorbatteries.typed

import com.lightningkite.ktorbatteries.jsonschema.encodeToSchema
import com.lightningkite.ktorbatteries.routes.*
import com.lightningkite.ktorbatteries.serialization.Serialization
import io.ktor.html.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import kotlinx.html.*
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

inline fun <reified T> FlowContent.form(
    title: String,
    jsEditorName: String,
    defaultValue: T? = null,
    collapsed: Boolean = false,
) = formUntyped(title, jsEditorName, typeOf<T>(), defaultValue, collapsed)

fun FlowContent.formUntyped(
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
                ${if(defaultValue != null) "${jsEditorName}.setValue(${Serialization.json.encodeToString(Serialization.module.serializer(type), defaultValue)})" else "" }
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

fun Route.helpFor(api: APIEndpoint<*, *, *, *>) = get {
    if(api.inputType == null) {
        context.respond(HttpStatusCode.NoContent)
        return@get
    }
    this.context.respondHtml {
        head {
            includeFormScript()
        }
        body {
            script {
                unsafe {
                    raw("""
                        async function submit() {
                            const r = await fetch("${api.route.fullPath}", { method: '${api.route.selector.maybeMethod?.value}', body: editor.getValue() })
                            const asJson = await r.json()
                            result.setValue(asJson)
                        }
                    """.trimIndent())
                }
            }
            h1 {
                +api.route.fullPath
            }
            formUntyped("Input", "editor", api.inputType)
            button(type = ButtonType.button, classes = "btn btn-primary") {
                onClick = "submit()"
                +"Submit"
            }
            displayUntyped("Output", "result", api.outputType, collapsed = true)
        }
    }
}

fun Route.apiHelp() {
    for(subj in API.subjects) {
        for(child in subj.children) {
            route(child.route.fullPath + "/" + child.route.selector.maybeMethod?.value) { helpFor(child) }.also { println(it.fullPath) }
        }
    }
}