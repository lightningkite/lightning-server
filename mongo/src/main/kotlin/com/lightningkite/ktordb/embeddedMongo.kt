package com.lightningkite.ktordb

import com.github.jershell.kbson.UUIDSerializer
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.reactivestreams.client.MongoClient
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.bson.UuidRepresentation
import org.litote.kmongo.coroutine.CoroutineClient
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.coroutine.toList
import org.litote.kmongo.reactivestreams.KMongo
import org.slf4j.LoggerFactory
import java.io.File
import de.flapdoodle.embed.mongo.Command
import de.flapdoodle.embed.mongo.MongodStarter
import de.flapdoodle.embed.mongo.config.*
import de.flapdoodle.embed.mongo.distribution.Version
import de.flapdoodle.embed.process.config.process.ProcessOutput
import de.flapdoodle.embed.process.runtime.Network
import java.io.Closeable
import java.nio.file.Files

fun testMongo(replFile: File = Files.createTempDirectory("embeddedMongo").toFile(), port: Int = Network.freeServerPort(Network.getLocalHost())): MongoClient
    = embeddedMongo(true, replFile, port)
fun embeddedMongo(replFile: File = File("./build/embeddedMongo"), port: Int = 54961): MongoClient
    = embeddedMongo(false, replFile, port)

private fun embeddedMongo(deleteAfter: Boolean, replFile: File, port: Int): MongoClient {
    val starter = MongodStarter.getInstance(
        Defaults.runtimeConfigFor(Command.MongoD, LoggerFactory.getLogger("embeddedMongo"))
            .processOutput(ProcessOutput.silent())
            .build()
    )
    val replFileExisted = replFile.exists() && replFile.list()?.isEmpty() == false
    replFile.mkdirs()
    val mongodConfig: MongodConfig = MongodConfig.builder()
        .version(Version.Main.PRODUCTION)
        .replication(Storage(replFile.toString(), "rs0", 128))
        .cmdOptions(
            MongoCmdOptions.builder()
                .useNoPrealloc(false)
                .useSmallFiles(false)
                .useNoJournal(false)
                .isVerbose(false)
                .build()
        )
        .net(Net(port, Network.localhostIsIPv6()))
        .build()
    val mongodExecutable = starter.prepare(mongodConfig)
    mongodExecutable.start()

    if(!replFileExisted) {
        runBlocking {
            try {
                KMongo
                    .createClient("mongodb://localhost:$port/")
                    .getDatabase("admin")
                    .runCommand(Document().apply {
                        append("replSetInitiate", Document().apply {
                            append("_id", "rs0")
                            append("members", listOf(Document().apply {
                                append("_id", 0)
                                append("host", "localhost:${port}")
                            }))
                        })
                    })
                    .toList()
            } catch (e: Exception) {
                mongodExecutable.stop()
                throw e
            }
        }
    }

    val wrapped = KMongo.createClient(
        MongoClientSettings.builder()
            .applyConnectionString(ConnectionString("mongodb://localhost:$port/?replicaSet=rs0"))
            .uuidRepresentation(UuidRepresentation.STANDARD)
            .build())
    var closeable: Closeable? = null
    var fromShutdownHook = false
    val shutdownHook = Thread {
        fromShutdownHook = true
        closeable?.close()
    }
    val client = object : MongoClient by wrapped {
        override fun close() {
            if(!fromShutdownHook) {
                Runtime.getRuntime().removeShutdownHook(shutdownHook)
            }
            mongodExecutable.stop()
            try {
                wrapped.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            if(deleteAfter) replFile.deleteRecursively()
        }
    }
    closeable = client
    Runtime.getRuntime().addShutdownHook(shutdownHook)
    return client
}