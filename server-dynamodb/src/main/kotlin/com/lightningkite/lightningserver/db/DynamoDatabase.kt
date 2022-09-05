package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.Database
import com.lightningkite.lightningdb.FieldCollection
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KType

class DynamoDatabase(val dynamo: DynamoDbAsyncClient): Database {
    private val collections = ConcurrentHashMap<String, Lazy<DynamoDbCollection<*>>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> collection(type: KType, name: String): DynamoDbCollection<T> =
        (collections.getOrPut(name) {
            lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                DynamoDbCollection(
                    dynamo,
                    Serialization.module.serializer(type) as KSerializer<T>,
                    name
                ).also {
                    runBlocking {
                        @Suppress("OPT_IN_USAGE")
                        it.prepare()
                    }
                }
            }
        } as Lazy<DynamoDbCollection<T>>).value
}