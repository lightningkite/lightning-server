package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.Condition
import kotlinx.serialization.KSerializer
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest
import software.amazon.awssdk.services.dynamodb.model.QueryRequest
import software.amazon.awssdk.services.dynamodb.model.ScanRequest
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest

fun <T> QueryRequest.Builder.apply(part: DynamoCondition<T>) {
    keyConditionExpression(part.builderKey!!.filter.toString())
    part.builderFilter?.filter?.toString()?.let { f -> filterExpression(f) }
    part.builder.nameMap.takeUnless { it.isEmpty() }?.let { m -> expressionAttributeNames(m) }
    part.builder.valueMap.takeUnless { it.isEmpty() }?.let { m -> expressionAttributeValues(m) }
}
fun <T> ScanRequest.Builder.apply(part: DynamoCondition<T>) {
    part.builderFilter?.filter?.toString()?.let { f -> filterExpression(f) }
    part.builder.nameMap.takeUnless { it.isEmpty() }?.let { m -> expressionAttributeNames(m) }
    part.builder.valueMap.takeUnless { it.isEmpty() }?.let { m -> expressionAttributeValues(m) }
}
fun <T> UpdateItemRequest.Builder.apply(part: DynamoCondition<T>, mod: DynamoModification<T>) {
    part.builderFilter?.filter?.toString()?.let { f -> conditionExpression(f) }
    mod.build(part.builder)?.let { updateExpression(it.filter.toString()) }
    part.builder.nameMap.takeUnless { it.isEmpty() }?.let { m -> expressionAttributeNames(m) }
    part.builder.valueMap.takeUnless { it.isEmpty() }?.let { m -> expressionAttributeValues(m) }
}
fun <T> DeleteItemRequest.Builder.apply(part: DynamoCondition<T>) {
    part.builderFilter?.filter?.toString()?.let { f -> conditionExpression(f) }
    part.builder.nameMap.takeUnless { it.isEmpty() }?.let { m -> expressionAttributeNames(m) }
    part.builder.valueMap.takeUnless { it.isEmpty() }?.let { m -> expressionAttributeValues(m) }
}