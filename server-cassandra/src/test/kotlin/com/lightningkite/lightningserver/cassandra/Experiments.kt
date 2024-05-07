package com.lightningkite.lightningserver.cassandra

import kotlin.test.Test
import com.datastax.oss.driver.api.core.*
import com.datastax.oss.driver.api.querybuilder.QueryBuilder.*
import com.datastax.oss.driver.api.querybuilder.*
import com.datastax.oss.driver.api.querybuilder.SchemaBuilder.*
import com.datastax.oss.driver.api.core.*
import com.datastax.oss.driver.api.core.cql.*
import com.datastax.oss.driver.api.core.type.DataTypes
import com.lightningkite.lightningserver.logging.LoggingSettings
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.testcontainers.containers.CassandraContainer

class Experiments {
    @Test fun test() {
        val container = CassandraContainer("cassandra:5")
        LoggingSettings()
        if(!container.isRunning()) container.start()
        CqlSession.builder()
            .addContactPoint(container.contactPoint)
            .withLocalDatacenter(container.localDatacenter)
            .build()
            .use { session ->
                runBlocking {
                    session.executeSuspending(createKeyspace("test").ifNotExists().withSimpleStrategy(1).build())
                    session.executeSuspending(createTable("test", "TestTable")
                        .withPartitionKey("id", DataTypes.UUID)
                        .withColumn("id", DataTypes.UUID)
                        .withColumn("title", DataTypes.TEXT)
                        .build()
                    )
                    session.executeSuspending(insertInto("test", "TestTable").value("id", literal(com.lightningkite.uuid())).value("title", literal("Test")).build())
                    session.executeSuspending(selectFrom("test", "TestTable").columns("id", "title").where().limit(1).build()).currentPage().forEach {
                        println("Got item $it")
                    }
                    session.metadata.getKeyspace("test").get().tables.forEach { (key, value) ->
                        println("---$key---")
                        value.columns.forEach { key, value ->
                            println("$key: ${value.type}")
                        }
                        value.indexes.values.forEach {
                            it.name
                        }
                    }
                }
            }
    }
}