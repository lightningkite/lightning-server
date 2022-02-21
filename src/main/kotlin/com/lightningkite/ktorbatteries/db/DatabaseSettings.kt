package com.lightningkite.ktorbatteries.db

import com.lightningkite.ktorbatteries.SetOnce
import com.lightningkite.ktorbatteries.SettingSingleton
import com.lightningkite.ktorkmongo.fixUuidSerialization
import com.lightningkite.ktorkmongo.testMongo
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import org.bson.UuidRepresentation
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.reactivestreams.KMongo
import java.io.File

var database: Database by SetOnce()