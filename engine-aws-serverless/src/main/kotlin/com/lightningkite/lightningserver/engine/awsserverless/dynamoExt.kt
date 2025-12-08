package com.lightningkite.lightningserver.engine.awsserverless

import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*


internal fun AttributeValue.Type.scalar(): ScalarAttributeType? = when (this) {
    AttributeValue.Type.S -> ScalarAttributeType.S
    AttributeValue.Type.N -> ScalarAttributeType.N
    AttributeValue.Type.B -> ScalarAttributeType.B
    else -> null
}

internal suspend fun DynamoDbAsyncClient.describeTableActiveOrNull(describeTableRequest: (DescribeTableRequest.Builder) -> Unit): DescribeTableResponse? {
    var once = true
    while (true) {
        try {
            val description = describeTable(describeTableRequest).await()
            when (description.table().tableStatus()) {
                TableStatus.ACTIVE -> return description
                TableStatus.ARCHIVED,
                TableStatus.REPLICATION_NOT_AUTHORIZED,
                TableStatus.INACCESSIBLE_ENCRYPTION_CREDENTIALS,
                    -> return null

                TableStatus.CREATING,
                TableStatus.UPDATING,
                TableStatus.DELETING,
                TableStatus.ARCHIVING,
                TableStatus.UNKNOWN_TO_SDK_VERSION,
                    -> delay(100)
            }
        } catch (e: ResourceNotFoundException) {
            if (once) {
                once = false
                delay(1000)
            } else {
                return null
            }
        }
    }
}

internal suspend fun DynamoDbAsyncClient.describeTableActive(describeTableRequest: (DescribeTableRequest.Builder) -> Unit): DescribeTableResponse? {
    var once = true
    while (true) {
        try {
            val description = describeTable(describeTableRequest).await()
            when (description.table().tableStatus()) {
                TableStatus.ACTIVE -> return description
                TableStatus.ARCHIVED,
                TableStatus.REPLICATION_NOT_AUTHORIZED,
                TableStatus.INACCESSIBLE_ENCRYPTION_CREDENTIALS,
                    -> throw Exception("Not possible...")

                TableStatus.CREATING,
                TableStatus.UPDATING,
                TableStatus.DELETING,
                TableStatus.ARCHIVING,
                TableStatus.UNKNOWN_TO_SDK_VERSION,
                    -> delay(100)
            }
        } catch (e: ResourceNotFoundException) {
            if (once) {
                once = false
                delay(1000)
            } else throw e
        }
    }
}

internal suspend fun DynamoDbAsyncClient.requireTable(
    createTableRequest: (CreateTableRequest.Builder) -> Unit,
    timeToLive: (UpdateTimeToLiveRequest.Builder) -> Unit,
) {
    val c = CreateTableRequest.builder().also(createTableRequest).build()
    val t = UpdateTimeToLiveRequest.builder().also(timeToLive).build()
    val tableName = c.tableName()
    val tableConfig = describeTableActiveOrNull { it.tableName(tableName) }?.table()
    val timeToLiveConfig = if (tableConfig == null) null else describeTimeToLive { it.tableName(tableName) }.await()
        .timeToLiveDescription()

    println("-------------- $tableName TABLE CONFIG $tableConfig")

    // check table config
    if (tableConfig != null) {
        val isBroken = c.attributeDefinitions().toSet() != tableConfig.attributeDefinitions().toSet() ||
                c.tableName() != tableConfig.tableName() ||
                c.keySchema() != tableConfig.keySchema() ||
                !c.localSecondaryIndexes().all { desired ->
                    tableConfig.localSecondaryIndexes().any { existing ->
                        desired.keySchema() == existing.keySchema() &&
                                desired.indexName() == existing.indexName() &&
                                desired.projection() == existing.projection()
                    }
                } ||
                !c.globalSecondaryIndexes().all { desired ->
                    tableConfig.globalSecondaryIndexes().any { existing ->
                        desired.keySchema() == existing.keySchema() &&
                                desired.indexName() == existing.indexName() &&
                                desired.projection() == existing.projection()
                    }
                } ||
                c.streamSpecification() != tableConfig.streamSpecification() ||
                (c.deletionProtectionEnabled() != null && c.deletionProtectionEnabled() != tableConfig.deletionProtectionEnabled()) ||
                t.timeToLiveSpecification().attributeName() != timeToLiveConfig?.attributeName()
        if (!isBroken) return
        // TODO: Be kinder and update if possible
        println("-------------- $tableName BROKEN, KILL")
        println(
            "-------------- $tableName ${
                c.attributeDefinitions().toSet() != tableConfig.attributeDefinitions().toSet()
            } c.attributeDefinitions() (${c.attributeDefinitions()}) != tableConfig.attributeDefinitions() (${tableConfig.attributeDefinitions()})"
        )
        println("-------------- $tableName ${c.tableName() != tableConfig.tableName()} c.tableName() (${c.tableName()}) != tableConfig.tableName() (${tableConfig.tableName()})")
        println("-------------- $tableName ${c.keySchema() != tableConfig.keySchema()} c.keySchema() (${c.keySchema()}) != tableConfig.keySchema() (${tableConfig.keySchema()})")
        println(
            "-------------- $tableName ${
                !c.localSecondaryIndexes().all { desired ->
                    tableConfig.localSecondaryIndexes().any { existing ->
                        desired.keySchema() == existing.keySchema() &&
                                desired.indexName() == existing.indexName() &&
                                desired.projection() == existing.projection()
                    }
                }
            } c.localSecondaryIndexes() != tableConfig.localSecondaryIndexes() (${tableConfig.localSecondaryIndexes()})"
        )
        println(
            "-------------- $tableName ${
                !c.globalSecondaryIndexes().all { desired ->
                    tableConfig.globalSecondaryIndexes().any { existing ->
                        desired.keySchema() == existing.keySchema() &&
                                desired.indexName() == existing.indexName() &&
                                desired.projection() == existing.projection()
                    }
                }
            } c.globalSecondaryIndexes() (${c.globalSecondaryIndexes()}) != tableConfig.globalSecondaryIndexes() (${tableConfig.globalSecondaryIndexes()})")
        println("-------------- $tableName ${c.provisionedThroughput() != null && c.provisionedThroughput() != tableConfig.provisionedThroughput()} c.provisionedThroughput() != null && c.provisionedThroughput() (${c.provisionedThroughput()}) != tableConfig.provisionedThroughput() (${tableConfig.provisionedThroughput()}))")
        println("-------------- $tableName ${c.streamSpecification() != tableConfig.streamSpecification()} c.streamSpecification() != tableConfig.streamSpecification() (${tableConfig.streamSpecification()})")
        println("-------------- $tableName ${c.deletionProtectionEnabled() != null && c.deletionProtectionEnabled() != tableConfig.deletionProtectionEnabled()} c.deletionProtectionEnabled() != null && c.deletionProtectionEnabled() (${c.deletionProtectionEnabled()}) != tableConfig.deletionProtectionEnabled() (${tableConfig.deletionProtectionEnabled()}))")
        println(
            "-------------- $tableName ${
                t.timeToLiveSpecification().attributeName() != timeToLiveConfig?.attributeName()
            } t.timeToLiveSpecification().attributeName()  (${
                t.timeToLiveSpecification().attributeName()
            }) != timeToLiveConfig?.attributeName() (${timeToLiveConfig?.attributeName()}) "
        )
        deleteTable { it.tableName(tableName) }.await()
        println("-------------- $tableName BROKEN, KILL AWAIT")
        describeTableActiveOrNull { it.tableName(tableName) }
    }
    println("-------------- $tableName TABLE CREATE")
    createTable(c).await()
    println("-------------- $tableName TABLE CREATE AWAIT")
    describeTableActive { it.tableName(tableName) }
    println("-------------- $tableName TABLE TTL")
    updateTimeToLive(t).await()
    println("-------------- $tableName TABLE AWAIT")
    describeTableActive { it.tableName(tableName) }
    println("-------------- $tableName TABLE OK!")
}