package com.lightningkite.ktorbatteries.jsonschema

import com.lightningkite.ktorbatteries.auto.defaults
import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import com.lightningkite.ktorbatteries.settings.runServer
import com.lightningkite.ktorbatteries.typed.*
import com.lightningkite.ktorkmongo.and
import com.lightningkite.ktorkmongo.eq
import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.routing.*
import kotlinx.html.body
import kotlinx.html.form
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.properties.Properties
import org.junit.Test
import java.io.File
import kotlinx.html.stream.appendHTML
import kotlinx.serialization.properties.encodeToStringMap
import java.net.URLEncoder
import java.util.*
import kotlin.reflect.typeOf

class SchemaTest {
    @Test fun quick() {
        println(Json.encodeToSchema(Post.serializer()))
        println(Json.encodeToSchema(PostModification.serializer()))
    }
    @Test fun params() {
        println(Properties.encodeToStringMap(PostFields.author eq "Bill" and (PostFields.title eq "Bills Greatest")).entries.joinToString("&") { it.key + "=" + URLEncoder.encode(it.value, Charsets.UTF_8) })
    }
    @Test fun makeFormFile() {
        File("./build/out/form.html").apply { parentFile.mkdirs() }.bufferedWriter().use {
            it.appendHTML().html {
                head { this.includeFormScript() }
                body {
                    form("testThing", "testThing", Post(author = "Jack Knife", title = "101 Ways to Cut", content = "Leftways\nRightways\nDownwards\nAnd more!"))
                    form<PostModification>("testThingChange", "testThingChange", collapsed = true )
                }
            }
        }
    }
    @Test fun helpTest() {
        Settings(server = GeneralServerSettings(port = 8081))
        runServer {
            defaults()
            routing {
                val notes = arrayListOf<String>("videoA", "videoB")
                subject("notes") { user: TestPrincipal? ->
                    notes
                }.apply {
                    get { _ -> this }
                    this.post { user, subj: String -> notes.add(subj) }

                    route.subject("{id}") { user: TestPrincipal? ->
                        notes[parameters["id"]!!.toInt()]
                    }.apply {
                        get { _ -> this }
                        deleteParams { user, subj: String -> notes.add(subj) }
                    }
                }
                route("api-docs") {
                    apiHelp()
                }
            }
        }
    }
}

private class TestPrincipal: Principal