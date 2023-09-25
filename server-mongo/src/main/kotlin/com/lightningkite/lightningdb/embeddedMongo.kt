package com.lightningkite.lightningdb

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.kotlin.client.coroutine.MongoClient
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.bson.UuidRepresentation
import org.slf4j.LoggerFactory
import java.io.File
import de.flapdoodle.embed.mongo.packageresolver.Command
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
        .version(Version.Main.V6_0)
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
                MongoClient
                    .create("mongodb://localhost:$port/")
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

    val client = MongoClient.create(
        MongoClientSettings.builder()
            .apply {

                applyConnectionString(ConnectionString("mongodb://localhost:$port/?replicaSet=rs0"))
                uuidRepresentation(UuidRepresentation.STANDARD)
            }
            .build()
    )


    Runtime.getRuntime().addShutdownHook(Thread {
        try {
            client.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mongodExecutable.stop()
        if (deleteAfter) replFile.deleteRecursively()
    })
    return client
}