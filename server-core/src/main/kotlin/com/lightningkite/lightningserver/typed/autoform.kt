package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.jsonschema.encodeToSchema
import com.lightningkite.lightningserver.jsonschema.internal.createJsonSchema
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningdb.ServerFile
import com.lightningkite.lightningserver.files.UploadEarlyEndpoint
import com.lightningkite.lightningserver.routes.fullUrl

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
    uploadEarlyEndpoint: UploadEarlyEndpoint? = UploadEarlyEndpoint.default,
): Unit =
    insideHtmlForm(title, jsEditorName, Serialization.module.serializer(), defaultValue, collapsed, uploadEarlyEndpoint)

fun <T> FORM.insideHtmlForm(
    title: String,
    jsEditorName: String,
    serializer: KSerializer<T>,
    defaultValue: T? = null,
    collapsed: Boolean = false,
    uploadEarlyEndpoint: UploadEarlyEndpoint? = UploadEarlyEndpoint.default,
): Unit {
    this.encType = FormEncType.multipartFormData
    input(InputType.hidden, name = "__json") {
        id = "$jsEditorName-input"
    }
    script {
        unsafe {
            //language=JavaScript
            raw(
                """
                const fileEditorSet = new Map([${
                    serializer.descriptor.fileFieldNames().joinToString {
                        "[\"$title${it.joinToString { "[$it]" }}\", \"$title.${it.joinToString(separator = ".")}\"]"
                    }
                }]);
                const obs = new MutationObserver((mutations) => {
                    window.setTimeout(()=>{
                      for(const mut of mutations) {
                        for(const added of mut.addedNodes) {
                          if(!(added instanceof HTMLElement)) continue;
                          for(const [name, path] of fileEditorSet) {
                            const part = added.querySelector(`label[for="${'$'}{name}"]`);
                            if(!part) continue;
                            const input = document.createElement('input');
                            input.type = 'file';
                            input.onchange = async (ev) => {
                              const urlsResponse = await fetch("${uploadEarlyEndpoint?.endpoint?.path?.fullUrl()}");
                              const urls = await urlsResponse.json();
                              const uploadResult = await fetch(urls.uploadUrl, {
                                method: "PUT",
                                body: input.files[0],
                                headers: {
                                  'x-ms-blob-type': 'BlockBlob'
                                }
                              });
                              if(uploadResult.ok) {
                                window.${jsEditorName}.getEditor(path).setValue(urls.futureCallToken);
                              }
                            };
                            part.after(input);
                          }
                        }
                      }
                    }, 10);
                }).observe(document.getElementById('${this@insideHtmlForm.id}'), { childList: true });
                window.${jsEditorName}_mutObs = obs;
                """.trimIndent()
            )
        }
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
            //language=JavaScript
            raw(
                """
                $jsEditorName.on('change', function () {
                    document.querySelector('#$jsEditorName-input').value = JSON.stringify(${jsEditorName}.getValue())
                });
                """.trimIndent()
            )
        }
    }
    if (uploadEarlyEndpoint != null) {
        h3 {
            id = "uploaderHeader"
            +"Uploader +"
        }
        div {
            id = "uploaderSection"
            hidden = true
            input(type = InputType.file) {
                id = "fileUploaderInput"
            }
            textInput { id = "fileUploaderResult" }
            button(type = ButtonType.button) {
                id = "fileUploaderCopy"
                +"Copy to Clipboard"
            }
        }
        script {
            unsafe {
                raw(
                    """
                document.getElementById("uploaderHeader").onclick = ev => {
                    const section = document.getElementById("uploaderSection")
                    section.hidden = !section.hidden
                }
                document.getElementById("fileUploaderCopy").onclick = ev => {
                    navigator.clipboard.writeText(document.getElementById("fileUploaderResult").value)
                }
                const input = document.getElementById("fileUploaderInput")
                input.onchange = async (ev) => {
                  const urlsResponse = await fetch("${uploadEarlyEndpoint.endpoint.path.fullUrl()}");
                  const urls = await urlsResponse.json();
                  const uploadResult = await fetch(urls.uploadUrl, {
                    method: "PUT",
                    body: input.files[0],
                    headers: {
                      'x-ms-blob-type': 'BlockBlob'
                    }
                  });
                  if(uploadResult.ok) {
                    document.getElementById("fileUploaderResult").value = urls.futureCallToken
                  }
                };
            """.trimIndent()
                )
            }
        }
    }
}

internal fun SerialDescriptor.fileFieldNames(visited: MutableSet<SerialDescriptor> = mutableSetOf()): List<List<String>> {
    if (!visited.add(this)) return listOf()
    return (0 until elementsCount)
        .flatMap {
            val name = getElementName(it)
            var descriptor = getElementDescriptor(it)
            if (descriptor.kind == SerialKind.CONTEXTUAL) {
                descriptor = Serialization.module.getContextualDescriptor(descriptor)!!
            }
            descriptor.fileFieldNames(visited)
                .map { listOf(name) + it } + if (descriptor == Serialization.module.getContextual(
                    ServerFile::class
                )?.descriptor
            ) listOf(
                listOf(name)
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
                window.${jsEditorName} = $jsEditorName
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