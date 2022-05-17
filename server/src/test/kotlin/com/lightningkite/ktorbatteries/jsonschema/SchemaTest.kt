@file:UseContextualSerialization(UUID::class)
package com.lightningkite.ktorbatteries.jsonschema

import com.lightningkite.ktorbatteries.auto.defaults
import com.lightningkite.ktorbatteries.serialization.Serialization
import com.lightningkite.ktorbatteries.settings.GeneralServerSettings
import com.lightningkite.ktorbatteries.settings.runServer
import com.lightningkite.ktorbatteries.typed.*
import com.lightningkite.ktordb.*
import io.ktor.server.auth.*
import kotlinx.html.body
import kotlinx.html.form
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.serialization.json.Json
import kotlinx.serialization.properties.Properties
import org.junit.Test
import java.io.File
import kotlinx.html.stream.appendHTML
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.capturedKClass
import kotlinx.serialization.descriptors.getContextualDescriptor
import kotlinx.serialization.properties.encodeToStringMap
import java.net.URLEncoder
import java.util.*
import kotlin.reflect.typeOf

class SchemaTest {

    @Test
    fun quick() {
        println(Json.encodeToSchema(Post.serializer()))
    }

    @Test
    fun params() {
        println(
            Properties.encodeToStringMap(Post.chain.condition { (it.author eq "Bill") and (it.title eq "Bills Greatest") }).entries.joinToString(
                "&"
            ) { it.key + "=" + URLEncoder.encode(it.value, Charsets.UTF_8) })
    }
//    @Test fun makeFormFile() {
//        File("./build/out/form.html").apply { parentFile.mkdirs() }.bufferedWriter().use {
//            it.appendHTML().html {
//                head { this.includeFormScript() }
//                body {
//                    form(
//                        "testThing",
//                        "testThing",
//                        Post(
//                            author = "Jack Knife",
//                            title = "101 Ways to Cut",
//                            content = "Leftways\nRightways\nDownwards\nAnd more!"
//                        )
//                    )
//                    form<Modification<Post>>("testThingChange", "testThingChange", collapsed = true)
//                }
//            }
//        }
//    }
//    @Test fun helpTest() {
//        Settings(server = GeneralServerSettings(port = 8081))
//        runServer {
//            defaults()
//            routing {
//                val notes = arrayListOf<String>("videoA", "videoB")
//                subject("notes") { user: TestPrincipal? ->
//                    notes
//                }.apply {
//                    get { _ -> this }
//                    this.post { user, subj: String -> notes.add(subj) }
//
//                    route.subject("{id}") { user: TestPrincipal? ->
//                        parameters["id"]!!.toInt()
//                    }.apply {
//                        get { _ -> notes[this] }
//                        delete { user -> notes.removeAt(this) }
//                    }
//                }
//                route("api-docs") {
//                    apiHelp()
//                }
//
//                subject("mongoModel") { user: TestPrincipal? -> Post.mongo }.apply {
//                    get { _, params: Query<Post> -> this.query(params) }
//                    post("query") { _, params: Query<Post> -> this.query(params) }
//                    post { _, params: Post -> this.insertOne(params) }
//                    patch { _, params: MassModification<Post> -> this.updateMany(params) }
//                    subject("{id}") { user: TestPrincipal? -> parameters["id"]!!.toUUID() }.apply {
//                        get { _ -> Post.mongo.get(this) ?: throw NotFoundException() }
//                        patch { _, params: Modification<Post> -> Post.mongo.updateOneById(this, params) }
//                    }
//                }
//            }
//        }
//    }
}


private class TestPrincipal : Principal