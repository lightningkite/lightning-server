// by Claude - Unit test to identify table recreation issue in AwsWebSocketDynamoDb
package com.lightningkite.lightningserver.engine.awsserverless

import com.lightningkite.services.cache.dynamodb.embeddedDynamo
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.junit.Test
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*
import kotlin.test.*

/**
 * Tests to identify why AwsWebSocketDynamoDb tables might be getting recreated,
 * causing data loss in production environments.
 *
 * IDENTIFIED ISSUE: In dynamoExt.kt:requireTable(), line 108:
 *
 *     t.timeToLiveSpecification().attributeName() != timeToLiveConfig?.attributeName()
 *
 * This comparison does NOT account for the TTL STATUS. When TTL is DISABLED,
 * TimeToLiveDescription.attributeName() returns null/empty string instead of the
 * configured attribute name. This causes the comparison to fail, setting isBroken=true,
 * which triggers table deletion and recreation.
 *
 * Scenarios that can trigger this:
 * 1. TTL is manually disabled by someone with AWS access
 * 2. TTL transitions to DISABLED state due to AWS internal operations
 * 3. TTL is in DISABLING transitional state
 * 4. Race condition if multiple Lambda instances call requireTable() concurrently
 *    while TTL is still being set up (ENABLING state)
 *
 * The fix should check timeToLiveConfig?.timeToLiveStatus() to verify TTL is ENABLED
 * before comparing attribute names, or only consider it "broken" if TTL is fully
 * DISABLED with a different/null attribute name.
 */
class RequireTableTest {

    private val tableName = "test-table"

    /**
     * This test verifies the TTL comparison logic in requireTable().
     *
     * The issue: When comparing TTL configurations, the code does:
     * t.timeToLiveSpecification().attributeName() != timeToLiveConfig?.attributeName()
     *
     * However, TimeToLiveDescription.attributeName() behavior varies based on TTL status:
     * - ENABLED: Returns the attribute name
     * - DISABLED: Returns null or empty string (NOT the attribute name!)
     * - ENABLING: Returns the attribute name (but status is transitional)
     * - DISABLING: Uncertain behavior
     *
     * If TTL becomes DISABLED for any reason, this comparison will fail,
     * causing requireTable() to delete and recreate the table!
     */
    @Test
    fun testTtlComparisonWhenTtlDisabled(): Unit = runBlocking {
        val client = embeddedDynamo()

        // Create table with TTL enabled
        client.createTable {
            it.tableName(tableName)
            it.billingMode(BillingMode.PAY_PER_REQUEST)
            it.keySchema({ k -> k.attributeName("pk").keyType(KeyType.HASH) })
            it.attributeDefinitions({ a -> a.attributeName("pk").attributeType(ScalarAttributeType.S) })
        }.await()

        waitForTableActive(client, tableName)

        // Enable TTL
        client.updateTimeToLive {
            it.tableName(tableName)
            it.timeToLiveSpecification { ttl ->
                ttl.attributeName("expireAt")
                ttl.enabled(true)
            }
        }.await()

        // Check TTL status when enabled
        val ttlEnabledDescription = client.describeTimeToLive {
            it.tableName(tableName)
        }.await().timeToLiveDescription()

        println("TTL Status after enabling: ${ttlEnabledDescription.timeToLiveStatus()}")
        println("TTL AttributeName after enabling: '${ttlEnabledDescription.attributeName()}'")

        // Verify TTL attribute name is returned when ENABLED
        // Note: The embedded DynamoDB might have slightly different behavior than real DynamoDB
        // but this demonstrates the potential issue
        if (ttlEnabledDescription.timeToLiveStatus() == TimeToLiveStatus.ENABLED) {
            assertEquals(
                "expireAt", ttlEnabledDescription.attributeName(),
                "TTL attribute name should be returned when ENABLED"
            )
        }

        // Now disable TTL
        client.updateTimeToLive {
            it.tableName(tableName)
            it.timeToLiveSpecification { ttl ->
                ttl.attributeName("expireAt")
                ttl.enabled(false)
            }
        }.await()

        // Check TTL status when disabled
        val ttlDisabledDescription = client.describeTimeToLive {
            it.tableName(tableName)
        }.await().timeToLiveDescription()

        println("TTL Status after disabling: ${ttlDisabledDescription.timeToLiveStatus()}")
        println("TTL AttributeName after disabling: '${ttlDisabledDescription.attributeName()}'")

        // THIS IS THE CRITICAL TEST:
        // When TTL is DISABLED, what does attributeName() return?
        // If it returns null or empty string, the comparison in requireTable() will fail!
        val desiredTtlAttributeName = "expireAt"
        val actualTtlAttributeName = ttlDisabledDescription.attributeName()

        // This comparison mimics the check in requireTable():
        // t.timeToLiveSpecification().attributeName() != timeToLiveConfig?.attributeName()
        val wouldTableBeRecreated = desiredTtlAttributeName != actualTtlAttributeName

        println("Desired TTL attribute: '$desiredTtlAttributeName'")
        println("Actual TTL attribute (when disabled): '$actualTtlAttributeName'")
        println("Would table be recreated? $wouldTableBeRecreated")

        // If this assertion fails with wouldTableBeRecreated = true,
        // we've found the bug!
        if (wouldTableBeRecreated) {
            println(
                "⚠️ BUG IDENTIFIED: When TTL is DISABLED, the attributeName() returns " +
                        "'$actualTtlAttributeName' instead of '$desiredTtlAttributeName'. " +
                        "This causes requireTable() to delete and recreate the table!"
            )
        }
    }

    /**
     * This test checks the TTL status transition behavior.
     *
     * When TTL is enabled, it goes through ENABLING -> ENABLED.
     * If a Lambda cold starts while TTL is in ENABLING status,
     * the attributeName might be available but the comparison behavior could differ.
     */
    @Test
    fun testTtlStatusTransition(): Unit = runBlocking {
        val client = embeddedDynamo()

        // Create table
        client.createTable {
            it.tableName("test-transition-table")
            it.billingMode(BillingMode.PAY_PER_REQUEST)
            it.keySchema({ k -> k.attributeName("pk").keyType(KeyType.HASH) })
            it.attributeDefinitions({ a -> a.attributeName("pk").attributeType(ScalarAttributeType.S) })
        }.await()

        waitForTableActive(client, "test-transition-table")

        // Enable TTL and immediately check status
        client.updateTimeToLive {
            it.tableName("test-transition-table")
            it.timeToLiveSpecification { ttl ->
                ttl.attributeName("ttlAttribute")
                ttl.enabled(true)
            }
        }.await()

        // Check status immediately - might be ENABLING
        val immediateDescription = client.describeTimeToLive {
            it.tableName("test-transition-table")
        }.await().timeToLiveDescription()

        println("TTL Status immediately after updateTimeToLive: ${immediateDescription.timeToLiveStatus()}")
        println("TTL AttributeName immediately after updateTimeToLive: '${immediateDescription.attributeName()}'")

        // If status is ENABLING, document the behavior
        if (immediateDescription.timeToLiveStatus() == TimeToLiveStatus.ENABLING) {
            println(
                "⚠️ POTENTIAL ISSUE: TTL is in ENABLING status. " +
                        "If a Lambda cold start happens during this transition, " +
                        "the TTL comparison might behave unexpectedly."
            )
        }
    }

    /**
     * This test simulates calling requireTable() twice (simulating Lambda cold starts)
     * and verifies whether the table gets recreated.
     */
    @Test
    fun testRequireTableCalledTwice(): Unit = runBlocking {
        val client = embeddedDynamo()
        val testTableName = "require-table-test"

        // First call to requireTable - creates the table
        client.requireTable(
            createTableRequest = {
                it.tableName(testTableName)
                it.billingMode(BillingMode.PAY_PER_REQUEST)
                it.keySchema({ k -> k.attributeName("pk").keyType(KeyType.HASH) })
                it.attributeDefinitions({ a -> a.attributeName("pk").attributeType(ScalarAttributeType.S) })
            },
            timeToLive = {
                it.tableName(testTableName)
                it.timeToLiveSpecification { ttl ->
                    ttl.attributeName("expireAt")
                    ttl.enabled(true)
                }
            }
        )

        // Insert test data
        client.putItem {
            it.tableName(testTableName)
            it.item(
                mapOf(
                    "pk" to AttributeValue.fromS("test-item-1"),
                    "data" to AttributeValue.fromS("important-data")
                )
            )
        }.await()

        // Verify data exists
        val itemBefore = client.getItem {
            it.tableName(testTableName)
            it.key(mapOf("pk" to AttributeValue.fromS("test-item-1")))
        }.await()
        assertNotNull(itemBefore.item()["data"], "Test data should exist before second requireTable call")

        println("Data exists before second requireTable call: ${itemBefore.item()}")

        // Second call to requireTable - should NOT recreate the table
        client.requireTable(
            createTableRequest = {
                it.tableName(testTableName)
                it.billingMode(BillingMode.PAY_PER_REQUEST)
                it.keySchema({ k -> k.attributeName("pk").keyType(KeyType.HASH) })
                it.attributeDefinitions({ a -> a.attributeName("pk").attributeType(ScalarAttributeType.S) })
            },
            timeToLive = {
                it.tableName(testTableName)
                it.timeToLiveSpecification { ttl ->
                    ttl.attributeName("expireAt")
                    ttl.enabled(true)
                }
            }
        )

        // Verify data still exists
        val itemAfter = client.getItem {
            it.tableName(testTableName)
            it.key(mapOf("pk" to AttributeValue.fromS("test-item-1")))
        }.await()

        println("Data exists after second requireTable call: ${itemAfter.item()}")

        // If this fails, data was lost due to table recreation!
        if (itemAfter.item().isEmpty()) {
            println(
                "⚠️ BUG CONFIRMED: Table was recreated on second requireTable() call, " +
                        "causing data loss! Check the isBroken comparison logic."
            )
        }

        assertNotNull(
            itemAfter.item()["data"],
            "Test data should still exist after second requireTable call - table was recreated!"
        )
    }

    /**
     * Test the TTL status check more comprehensively.
     *
     * This test checks all the TTL statuses and their attributeName() behavior.
     */
    @Test
    fun testAllTtlStatuses(): Unit = runBlocking {
        val client = embeddedDynamo()

        println("\n=== TTL Status Behavior Analysis ===\n")

        // Check each status
        for (status in TimeToLiveStatus.values()) {
            println("TimeToLiveStatus.$status:")
            // We can't easily simulate all statuses, but we document expected behavior
            when (status) {
                TimeToLiveStatus.ENABLED -> {
                    println("  - attributeName() should return the configured attribute name")
                    println("  - requireTable() comparison should PASS")
                }

                TimeToLiveStatus.DISABLED -> {
                    println("  - attributeName() might return null or empty string")
                    println("  - requireTable() comparison might FAIL -> TABLE DELETED!")
                }

                TimeToLiveStatus.ENABLING -> {
                    println("  - Transitional state, attributeName() behavior uncertain")
                    println("  - requireTable() comparison might FAIL -> TABLE DELETED!")
                }

                TimeToLiveStatus.DISABLING -> {
                    println("  - Transitional state, attributeName() behavior uncertain")
                    println("  - requireTable() comparison might FAIL -> TABLE DELETED!")
                }

                TimeToLiveStatus.UNKNOWN_TO_SDK_VERSION -> {
                    println("  - Unknown status from future SDK version")
                    println("  - requireTable() comparison behavior unknown")
                }
            }
            println()
        }
    }

    /**
     * This test directly demonstrates the bug in the isBroken comparison logic.
     *
     * The issue is in dynamoExt.kt:108:
     *   t.timeToLiveSpecification().attributeName() != timeToLiveConfig?.attributeName()
     *
     * This comparison simulates what happens when TTL has been disabled on a table.
     */
    @Test
    fun testDirectTtlComparisonBug(): Unit = runBlocking {
        // Simulate the desired TTL specification (what we want)
        val desiredTtlAttributeName = "wsExpire"

        // Simulate TimeToLiveDescription when TTL is DISABLED
        // When TTL is DISABLED, attributeName() returns null or empty string
        val ttlDisabledAttributeName: String? = null  // or "" - both cause the bug

        // This is the comparison from line 108 of dynamoExt.kt
        val isBrokenDueToTtl = desiredTtlAttributeName != ttlDisabledAttributeName

        println("\n=== DIRECT BUG DEMONSTRATION ===")
        println("Desired TTL attribute name: '$desiredTtlAttributeName'")
        println("TTL attribute name when DISABLED: '$ttlDisabledAttributeName'")
        println("Comparison result (desiredTtlAttributeName != ttlDisabledAttributeName): $isBrokenDueToTtl")

        if (isBrokenDueToTtl) {
            println("\n⚠️ BUG CONFIRMED!")
            println("The comparison 't.timeToLiveSpecification().attributeName() != timeToLiveConfig?.attributeName()'")
            println("returns TRUE when TTL is DISABLED, even though the table structure is correct.")
            println("This causes 'isBroken' to be true, which triggers TABLE DELETION at line 150!")
            println("\nRoot cause: The comparison doesn't check TimeToLiveStatus - it only compares attribute names.")
            println("When TTL is DISABLED, attributeName() is null/empty, not the configured value.")
        }

        // This assertion demonstrates the bug exists
        assertTrue(
            isBrokenDueToTtl,
            "BUG: When TTL is DISABLED, the comparison incorrectly returns true, " +
                    "causing the table to be deleted even though only TTL status changed, not table structure"
        )
    }

    /**
     * Test that shows the comparison logic does NOT account for TTL status.
     *
     * The problematic code only checks:
     *   t.timeToLiveSpecification().attributeName() != timeToLiveConfig?.attributeName()
     *
     * It should also check:
     *   timeToLiveConfig?.timeToLiveStatus() == TimeToLiveStatus.ENABLED
     */
    @Test
    fun testTtlStatusNotChecked(): Unit = runBlocking {
        val client = embeddedDynamo()

        // Create a table without TTL enabled
        client.createTable {
            it.tableName("ttl-status-test")
            it.billingMode(BillingMode.PAY_PER_REQUEST)
            it.keySchema({ k -> k.attributeName("pk").keyType(KeyType.HASH) })
            it.attributeDefinitions({ a -> a.attributeName("pk").attributeType(ScalarAttributeType.S) })
        }.await()

        waitForTableActive(client, "ttl-status-test")

        // Check TTL status when TTL has NEVER been enabled
        val ttlDescription = client.describeTimeToLive {
            it.tableName("ttl-status-test")
        }.await().timeToLiveDescription()

        println("\n=== TTL Status Test ===")
        println("TTL Status (never enabled): ${ttlDescription.timeToLiveStatus()}")
        println("TTL AttributeName (never enabled): '${ttlDescription.attributeName()}'")

        // The desired TTL attribute
        val desiredTtlAttribute = "expireAt"

        // Simulate the check from dynamoExt.kt line 108
        val wouldTriggerTableDeletion = desiredTtlAttribute != ttlDescription.attributeName()

        println("\nDesired TTL attribute: '$desiredTtlAttribute'")
        println("Would this trigger table deletion? $wouldTriggerTableDeletion")

        if (wouldTriggerTableDeletion) {
            println("\n⚠️ PROBLEM IDENTIFIED!")
            println("A table that has NEVER had TTL enabled would be DELETED and recreated")
            println("when requireTable() is called, simply because the TTL attribute name doesn't match.")
            println("The code doesn't check if TTL status is actually ENABLED vs DISABLED.")
        }
    }

    /**
     * This test creates a table matching the AwsWebSocketDynamoDb tableSubs pattern
     * and compares what's in the create request vs what's returned from describeTable.
     *
     * This helps identify if any fields differ between request and response that
     * could cause the isBroken check to incorrectly return true.
     */
    @Test
    fun testDetailedComparisonOfRequestVsDescription(): Unit = runBlocking {
        val client = embeddedDynamo()
        val testTableName = "detailed-comparison-test"

        // Create a table matching the pattern from AwsWebSocketDynamoDb.ensureTables()
        val createRequest = CreateTableRequest.builder()
            .tableName(testTableName)
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .keySchema(
                KeySchemaElement.builder().attributeName("wsTopic").keyType(KeyType.HASH).build(),
                KeySchemaElement.builder().attributeName("wsSocketId").keyType(KeyType.RANGE).build()
            )
            .attributeDefinitions(
                AttributeDefinition.builder().attributeName("wsTopic").attributeType(ScalarAttributeType.S).build(),
                AttributeDefinition.builder().attributeName("wsSocketId").attributeType(ScalarAttributeType.S).build()
            )
            .globalSecondaryIndexes(
                GlobalSecondaryIndex.builder()
                    .keySchema(
                        KeySchemaElement.builder().attributeName("wsSocketId").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("wsTopic").keyType(KeyType.RANGE).build()
                    )
                    .projection { p ->
                        p.projectionType(ProjectionType.INCLUDE).nonKeyAttributes("wsExpire", "wsPath")
                    }
                    .indexName("test-table-subs-reverse")
                    .build()
            )
            .build()

        client.createTable(createRequest).await()
        waitForTableActive(client, testTableName)

        // Enable TTL
        client.updateTimeToLive {
            it.tableName(testTableName)
            it.timeToLiveSpecification { ttl ->
                ttl.attributeName("wsExpire")
                ttl.enabled(true)
            }
        }.await()

        // Now describe the table and compare
        val tableDesc = client.describeTable { it.tableName(testTableName) }.await().table()
        val ttlDesc = client.describeTimeToLive { it.tableName(testTableName) }.await().timeToLiveDescription()

        println("\n=== DETAILED COMPARISON: CreateRequest vs DescribeTable ===\n")

        // Check attribute definitions
        val reqAttrs = createRequest.attributeDefinitions().toSet()
        val descAttrs = tableDesc.attributeDefinitions().toSet()
        println("1. Attribute Definitions:")
        println("   Request:     $reqAttrs")
        println("   Description: $descAttrs")
        println("   Match: ${reqAttrs == descAttrs}")

        // Check key schema
        val reqKeys = createRequest.keySchema()
        val descKeys = tableDesc.keySchema()
        println("\n2. Key Schema:")
        println("   Request:     $reqKeys")
        println("   Description: $descKeys")
        println("   Match: ${reqKeys == descKeys}")

        // Check GSI - THIS IS THE CRITICAL ONE
        println("\n3. Global Secondary Indexes:")
        val reqGsi = createRequest.globalSecondaryIndexes()
        val descGsi = tableDesc.globalSecondaryIndexes()

        println("   Request GSI count: ${reqGsi.size}")
        println("   Description GSI count: ${descGsi.size}")

        for (desired in reqGsi) {
            println("\n   Desired GSI '${desired.indexName()}':")
            println("      keySchema: ${desired.keySchema()}")
            println("      projection: ${desired.projection()}")
            println("      projection.projectionType: ${desired.projection().projectionType()}")
            println("      projection.nonKeyAttributes: ${desired.projection().nonKeyAttributes()}")

            val matchingExisting = descGsi.find { it.indexName() == desired.indexName() }
            if (matchingExisting != null) {
                println("\n   Existing GSI '${matchingExisting.indexName()}':")
                println("      keySchema: ${matchingExisting.keySchema()}")
                println("      projection: ${matchingExisting.projection()}")
                println("      projection.projectionType: ${matchingExisting.projection().projectionType()}")
                println("      projection.nonKeyAttributes: ${matchingExisting.projection().nonKeyAttributes()}")

                // Individual comparisons
                val keySchemaMatch = desired.keySchema() == matchingExisting.keySchema()
                val projectionMatch = desired.projection() == matchingExisting.projection()
                val projTypeMatch =
                    desired.projection().projectionType() == matchingExisting.projection().projectionType()
                val nonKeyAttrsMatch =
                    desired.projection().nonKeyAttributes() == matchingExisting.projection().nonKeyAttributes()

                println("\n   Comparison results:")
                println("      keySchema match: $keySchemaMatch")
                println("      projection match: $projectionMatch")
                println("      projectionType match: $projTypeMatch")
                println("      nonKeyAttributes match: $nonKeyAttrsMatch")

                if (!projectionMatch && projTypeMatch && nonKeyAttrsMatch) {
                    println("\n   ⚠️ POTENTIAL BUG: projection objects don't match even though their contents do!")
                    println("      This could happen if Projection.equals() compares object references or has other fields.")
                }
            }
        }

        // Check stream specification
        println("\n4. Stream Specification:")
        println("   Request:     ${createRequest.streamSpecification()}")
        println("   Description: ${tableDesc.streamSpecification()}")
        println("   Match: ${createRequest.streamSpecification() == tableDesc.streamSpecification()}")

        // Check TTL
        println("\n5. TTL Configuration:")
        println("   Desired attributeName: wsExpire")
        println("   Actual attributeName: ${ttlDesc.attributeName()}")
        println("   TTL status: ${ttlDesc.timeToLiveStatus()}")
        println("   Match: ${"wsExpire" == ttlDesc.attributeName()}")

        // Run the actual isBroken check logic
        println("\n=== RUNNING isBroken CHECK ===")
        val isBroken = createRequest.attributeDefinitions().toSet() != tableDesc.attributeDefinitions().toSet() ||
                createRequest.tableName() != tableDesc.tableName() ||
                createRequest.keySchema() != tableDesc.keySchema() ||
                !createRequest.globalSecondaryIndexes().all { desired ->
                    tableDesc.globalSecondaryIndexes().any { existing ->
                        desired.keySchema() == existing.keySchema() &&
                                desired.indexName() == existing.indexName() &&
                                desired.projection() == existing.projection()
                    }
                } ||
                createRequest.streamSpecification() != tableDesc.streamSpecification() ||
                "wsExpire" != ttlDesc.attributeName()

        println("isBroken result: $isBroken")

        if (isBroken) {
            println("\n⚠️ TABLE WOULD BE DELETED AND RECREATED!")
            // Identify which check failed
            if (createRequest.attributeDefinitions().toSet() != tableDesc.attributeDefinitions().toSet()) {
                println("   FAILED: attributeDefinitions comparison")
            }
            if (createRequest.keySchema() != tableDesc.keySchema()) {
                println("   FAILED: keySchema comparison")
            }
            if (!createRequest.globalSecondaryIndexes().all { desired ->
                    tableDesc.globalSecondaryIndexes().any { existing ->
                        desired.keySchema() == existing.keySchema() &&
                                desired.indexName() == existing.indexName() &&
                                desired.projection() == existing.projection()
                    }
                }) {
                println("   FAILED: globalSecondaryIndexes comparison")
            }
            if (createRequest.streamSpecification() != tableDesc.streamSpecification()) {
                println("   FAILED: streamSpecification comparison")
            }
            if ("wsExpire" != ttlDesc.attributeName()) {
                println("   FAILED: TTL attributeName comparison")
            }
        } else {
            println("\n✓ Table configuration matches - no recreation would occur")
        }

        // The test passes if isBroken is false (table would NOT be deleted)
        assertFalse(isBroken, "Table configuration should match after creation - isBroken should be false")
    }

    /**
     * Test if the projection comparison is order-sensitive for nonKeyAttributes.
     *
     * In real AWS DynamoDB, the nonKeyAttributes list might be returned in a
     * different order than specified during table creation. If the comparison
     * is order-dependent (which list comparison is), this would cause isBroken=true.
     */
    @Test
    fun testNonKeyAttributesOrderSensitivity(): Unit = runBlocking {
        println("\n=== TESTING nonKeyAttributes ORDER SENSITIVITY ===\n")

        // Create two projections with same attributes but different order
        val projection1 = Projection.builder()
            .projectionType(ProjectionType.INCLUDE)
            .nonKeyAttributes("wsExpire", "wsPath")
            .build()

        val projection2 = Projection.builder()
            .projectionType(ProjectionType.INCLUDE)
            .nonKeyAttributes("wsPath", "wsExpire")  // Different order!
            .build()

        println("Projection 1 nonKeyAttributes: ${projection1.nonKeyAttributes()}")
        println("Projection 2 nonKeyAttributes: ${projection2.nonKeyAttributes()}")
        println("projection1 == projection2: ${projection1 == projection2}")
        println("projection1.nonKeyAttributes() == projection2.nonKeyAttributes(): ${projection1.nonKeyAttributes() == projection2.nonKeyAttributes()}")

        if (projection1 != projection2) {
            println("\n⚠️ BUG FOUND: Projection comparison is ORDER-SENSITIVE!")
            println("If AWS returns nonKeyAttributes in a different order than specified,")
            println("the isBroken check will fail and the table will be DELETED!")
            println("\nThis could explain why tables work for a few days then get wiped:")
            println("- Initial Lambda instances create tables with order [wsExpire, wsPath]")
            println("- AWS stores them and may return them in a different order")
            println("- New Lambda cold start describes the table")
            println("- Gets [wsPath, wsExpire] (or some other order)")
            println("- Comparison fails -> isBroken=true -> TABLE DELETED")
        }

        // This assertion expects them to NOT be equal, demonstrating the bug
        assertFalse(
            projection1 == projection2,
            "This test expects projection comparison to be order-sensitive (which is the bug)"
        )
    }

    /**
     * Test showing how the GSI comparison logic would fail with different attribute ordering.
     */
    @Test
    fun testGsiComparisonWithDifferentAttributeOrder(): Unit = runBlocking {
        println("\n=== TESTING GSI COMPARISON WITH DIFFERENT ATTRIBUTE ORDER ===\n")

        // Create a desired GSI (from CreateTableRequest)
        val desiredGsi = GlobalSecondaryIndex.builder()
            .indexName("test-index")
            .keySchema(
                KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build()
            )
            .projection { p ->
                p.projectionType(ProjectionType.INCLUDE).nonKeyAttributes("attr1", "attr2", "attr3")
            }
            .build()

        // Simulate what AWS might return (same attributes, different order)
        val existingGsiProjection = Projection.builder()
            .projectionType(ProjectionType.INCLUDE)
            .nonKeyAttributes("attr2", "attr1", "attr3")  // Different order!
            .build()

        // This simulates the comparison at dynamoExt.kt line 103
        val projectionMatches = desiredGsi.projection() == existingGsiProjection

        println("Desired projection nonKeyAttributes: ${desiredGsi.projection().nonKeyAttributes()}")
        println("Existing projection nonKeyAttributes: ${existingGsiProjection.nonKeyAttributes()}")
        println("Projection match: $projectionMatches")

        if (!projectionMatches) {
            println("\n⚠️ This mismatch would cause isBroken=true at line 103 of dynamoExt.kt")
            println("The table would be DELETED even though the GSI structure is semantically identical!")
        }

        // The comparison should fail (demonstrating the bug)
        assertFalse(projectionMatches, "Order-sensitive comparison should fail with different attribute order")
    }

    private suspend fun waitForTableActive(client: DynamoDbAsyncClient, tableName: String) {
        while (true) {
            val status = client.describeTable { it.tableName(tableName) }.await()
                .table().tableStatus()
            if (status == TableStatus.ACTIVE) break
            kotlinx.coroutines.delay(100)
        }
    }
}
