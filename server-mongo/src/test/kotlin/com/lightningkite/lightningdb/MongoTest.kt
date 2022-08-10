package com.lightningkite.lightningdb

import com.github.jershell.kbson.UUIDSerializer
import com.lightningkite.lightningdb.application.defaultMongo
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.reactivestreams.client.MongoClient
import de.flapdoodle.embed.mongo.Command
import de.flapdoodle.embed.mongo.MongodExecutable
import de.flapdoodle.embed.mongo.MongodStarter
import de.flapdoodle.embed.mongo.config.*
import de.flapdoodle.embed.mongo.distribution.Version
import de.flapdoodle.embed.process.config.io.ProcessOutput
import de.flapdoodle.embed.process.runtime.Network
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.bson.UuidRepresentation
import org.junit.AfterClass
import org.junit.BeforeClass
import org.litote.kmongo.coroutine.CoroutineClient
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.coroutine.toList
import org.litote.kmongo.reactivestreams.KMongo
import org.slf4j.LoggerFactory
import java.io.File


abstract class MongoTest {
    companion object {
        var mongoClient: MongoClient? = null
        @BeforeClass @JvmStatic
        fun start() {
            mongoClient = testMongo()
            defaultMongo = mongoClient!!.database("default", ensureIndexesReady = true)
            prepareModels()
        }

        @AfterClass @JvmStatic
        fun after() {
            mongoClient?.close()
        }
    }
}