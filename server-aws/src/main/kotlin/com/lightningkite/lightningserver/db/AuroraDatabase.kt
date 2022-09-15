package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.cache.CacheSettings
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.cache.set
import com.lightningkite.lightningserver.schedule.schedule
import com.lightningkite.lightningserver.settings.Settings
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.tasks.startup
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import org.jetbrains.exposed.sql.Database
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.rds.RdsAsyncClient
import software.amazon.awssdk.services.rds.RdsClient
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KProperty1
import kotlin.reflect.KType

class AuroraDatabase(
    val identifier: String,
    val user: String,
    val password: String,
    val databaseName: String
) : com.lightningkite.lightningdb.Database {
    companion object {
        init {
            // auroradb-autopause://user:password@endpoint/database
            DatabaseSettings.register("auroradb-autopause") {
                val withoutScheme = it.url.substringAfter("://")
                val auth = withoutScheme.substringBefore('@', "")
                val user = auth.substringBefore(':').takeUnless { it.isEmpty() } ?: throw Exception()
                val password = auth.substringAfter(':').takeUnless { it.isEmpty() } ?: throw Exception()
                val dest = withoutScheme.substringAfter('@')
                val identifier = dest.substringBefore('/')
                val databaseName = dest.substringAfter('/')
                AuroraDatabase(identifier, user, password, databaseName)
            }
        }
        fun shutdownSchedule(frequency: Duration) = schedule("shutdown-unused-clusters", frequency) {
            Settings.requirements.values.filterIsInstance<DatabaseSettings>()
                .filter { it.url.startsWith("auroradb-autopause://") }
                .forEach {
                    val db = it() as AuroraDatabase
                    val count = dbUsageCache.get<Int>(db.identifier)
                    if(count == null || count == 0) {
                        println("Pausing ${db.identifier}...")
                        rds.stopDBCluster {
                            it.dbClusterIdentifier(db.identifier)
                        }
                        println("Paused ${db.identifier}.")
                    }
                    dbUsageCache.set(db.identifier, 0)
                }
        }
        val rds by lazy { RdsClient.builder().build() }
        val dbUsageCache by lazy {
            DynamoDbCache(DynamoDbAsyncClient.create(), "${generalSettings().projectName.filter { it.isLetter() }}AuroraCounts")
        }
    }

    private suspend inline fun <T> T.markRequest(): T {
        dbUsageCache.add(identifier, 1)
        return this
    }

    inner class UsageCollection<T: Any>(
        db: Database,
        name: String,
        serializer: KSerializer<T>,
    ): FieldCollection<T> {
        val raw = PostgresCollection<T>(db, name, serializer)
        override val wraps: FieldCollection<T> get() = raw
        override suspend fun fullCondition(condition: Condition<T>): Condition<T> = raw.fullCondition(condition)
        override suspend fun mask(): Mask<T> = raw.mask()
        override suspend fun find(
            condition: Condition<T>,
            orderBy: List<SortPart<T>>,
            skip: Int,
            limit: Int,
            maxQueryMs: Long,
        ): Flow<T> = raw.find(condition, orderBy, skip, limit, maxQueryMs).markRequest()
        override suspend fun count(condition: Condition<T>): Int = raw.count(condition).markRequest()
        override suspend fun <Key> groupCount(condition: Condition<T>, groupBy: KProperty1<T, Key>): Map<Key, Int> = raw.groupCount(condition, groupBy).markRequest()
        override suspend fun <N : Number?> aggregate(
            aggregate: Aggregate,
            condition: Condition<T>,
            property: KProperty1<T, N>,
        ): Double? = raw.aggregate(aggregate, condition, property).markRequest()
        override suspend fun <N : Number?, Key> groupAggregate(
            aggregate: Aggregate,
            condition: Condition<T>,
            groupBy: KProperty1<T, Key>,
            property: KProperty1<T, N>,
        ): Map<Key, Double?> = raw.groupAggregate(aggregate, condition, groupBy, property).markRequest()
        override suspend fun insert(models: List<T>): List<T> = raw.insert(models).markRequest()
        override suspend fun replaceOne(condition: Condition<T>, model: T): EntryChange<T> = raw.replaceOne(condition, model).markRequest()
        override suspend fun replaceOneIgnoringResult(condition: Condition<T>, model: T): Boolean = raw.replaceOneIgnoringResult(condition, model).markRequest()
        override suspend fun upsertOne(
            condition: Condition<T>,
            modification: Modification<T>,
            model: T,
        ): EntryChange<T> = raw.upsertOne(condition, modification, model).markRequest()
        override suspend fun upsertOneIgnoringResult(
            condition: Condition<T>,
            modification: Modification<T>,
            model: T,
        ): Boolean = raw.upsertOneIgnoringResult(condition, modification, model).markRequest()
        override suspend fun updateOne(condition: Condition<T>, modification: Modification<T>): EntryChange<T> = raw.updateOne(condition, modification).markRequest()
        override suspend fun updateOneIgnoringResult(condition: Condition<T>, modification: Modification<T>): Boolean = raw.updateOneIgnoringResult(condition, modification).markRequest()
        override suspend fun updateMany(condition: Condition<T>, modification: Modification<T>): CollectionChanges<T> = raw.updateMany(condition, modification).markRequest()
        override suspend fun updateManyIgnoringResult(condition: Condition<T>, modification: Modification<T>): Int = raw.updateManyIgnoringResult(condition, modification).markRequest()
        override suspend fun deleteOne(condition: Condition<T>): T? = raw.deleteOne(condition).markRequest()
        override suspend fun deleteOneIgnoringOld(condition: Condition<T>): Boolean = raw.deleteOneIgnoringOld(condition).markRequest()
        override suspend fun deleteMany(condition: Condition<T>): List<T> = raw.deleteMany(condition).markRequest()
        override suspend fun deleteManyIgnoringOld(condition: Condition<T>): Int = raw.deleteManyIgnoringOld(condition).markRequest()
        override fun registerRawSignal(callback: suspend (CollectionChanges<T>) -> Unit) = raw.registerRawSignal(callback)
    }

    private val collections = ConcurrentHashMap<String, Lazy<UsageCollection<*>>>()

    val db by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val status = rds.describeDBClusters().dbClusters().find { it.dbClusterIdentifier() == identifier }!!
        if(status.status().lowercase().startsWith("stop")) {
            println("Resuming cluster $identifier...")
            rds.startDBCluster {
                it.dbClusterIdentifier(identifier)
            }
            println("Resumed cluster $identifier.")
        }
        while(status.status() != "Available") {
            val updatedStatus = rds.describeDBClusters().dbClusters().find { it.dbClusterIdentifier() == identifier }!!.status()
            println("Cluster status is $updatedStatus, waiting for Available")
            if(updatedStatus == "Available") break
            Thread.sleep(5000L)
        }
        val endpoint = rds.describeDBClusters().dbClusters().find { it.dbClusterIdentifier() == identifier }!!.endpoint()
        Database.connect("jdbc:postgresql://$endpoint/$databaseName", "org.postgresql.Driver", user, password)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> collection(type: KType, name: String): UsageCollection<T> =
        (collections.getOrPut(name) {
            lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                UsageCollection(
                    db,
                    name,
                    PostgresCollection.format.serializersModule.serializer(type) as KSerializer<T>
                )
            }
        } as Lazy<UsageCollection<T>>).value
}