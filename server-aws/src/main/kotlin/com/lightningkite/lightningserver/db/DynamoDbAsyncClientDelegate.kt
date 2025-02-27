package com.lightningkite.lightningserver.db

import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClientBuilder
import software.amazon.awssdk.services.dynamodb.model.*
import software.amazon.awssdk.services.dynamodb.paginators.*
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbAsyncWaiter
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

open class DynamoDbAsyncClientDelegate(private val basis: DynamoDbAsyncClient) : DynamoDbAsyncClient {
    override fun batchExecuteStatement(batchExecuteStatementRequest: BatchExecuteStatementRequest): CompletableFuture<BatchExecuteStatementResponse> {
        return basis.batchExecuteStatement(batchExecuteStatementRequest)
    }

    override fun batchExecuteStatement(batchExecuteStatementRequest: Consumer<BatchExecuteStatementRequest.Builder>): CompletableFuture<BatchExecuteStatementResponse> {
        return basis.batchExecuteStatement(batchExecuteStatementRequest)
    }

    override fun batchGetItem(batchGetItemRequest: BatchGetItemRequest): CompletableFuture<BatchGetItemResponse> {
        return basis.batchGetItem(batchGetItemRequest)
    }

    override fun batchGetItem(batchGetItemRequest: Consumer<BatchGetItemRequest.Builder>): CompletableFuture<BatchGetItemResponse> {
        return basis.batchGetItem(batchGetItemRequest)
    }

    override fun batchGetItemPaginator(batchGetItemRequest: BatchGetItemRequest): BatchGetItemPublisher {
        return basis.batchGetItemPaginator(batchGetItemRequest)
    }

    override fun batchGetItemPaginator(batchGetItemRequest: Consumer<BatchGetItemRequest.Builder>): BatchGetItemPublisher {
        return basis.batchGetItemPaginator(batchGetItemRequest)
    }

    override fun batchWriteItem(batchWriteItemRequest: BatchWriteItemRequest): CompletableFuture<BatchWriteItemResponse> {
        return basis.batchWriteItem(batchWriteItemRequest)
    }

    override fun batchWriteItem(batchWriteItemRequest: Consumer<BatchWriteItemRequest.Builder>): CompletableFuture<BatchWriteItemResponse> {
        return basis.batchWriteItem(batchWriteItemRequest)
    }

    override fun createBackup(createBackupRequest: CreateBackupRequest): CompletableFuture<CreateBackupResponse> {
        return basis.createBackup(createBackupRequest)
    }

    override fun createBackup(createBackupRequest: Consumer<CreateBackupRequest.Builder>): CompletableFuture<CreateBackupResponse> {
        return basis.createBackup(createBackupRequest)
    }

    override fun createGlobalTable(createGlobalTableRequest: CreateGlobalTableRequest): CompletableFuture<CreateGlobalTableResponse> {
        return basis.createGlobalTable(createGlobalTableRequest)
    }

    override fun createGlobalTable(createGlobalTableRequest: Consumer<CreateGlobalTableRequest.Builder>): CompletableFuture<CreateGlobalTableResponse> {
        return basis.createGlobalTable(createGlobalTableRequest)
    }

    override fun createTable(createTableRequest: CreateTableRequest): CompletableFuture<CreateTableResponse> {
        return basis.createTable(createTableRequest)
    }

    override fun createTable(createTableRequest: Consumer<CreateTableRequest.Builder>): CompletableFuture<CreateTableResponse> {
        return basis.createTable(createTableRequest)
    }

    override fun deleteBackup(deleteBackupRequest: DeleteBackupRequest): CompletableFuture<DeleteBackupResponse> {
        return basis.deleteBackup(deleteBackupRequest)
    }

    override fun deleteBackup(deleteBackupRequest: Consumer<DeleteBackupRequest.Builder>): CompletableFuture<DeleteBackupResponse> {
        return basis.deleteBackup(deleteBackupRequest)
    }

    override fun deleteItem(deleteItemRequest: DeleteItemRequest): CompletableFuture<DeleteItemResponse> {
        return basis.deleteItem(deleteItemRequest)
    }

    override fun deleteItem(deleteItemRequest: Consumer<DeleteItemRequest.Builder>): CompletableFuture<DeleteItemResponse> {
        return basis.deleteItem(deleteItemRequest)
    }

    override fun deleteTable(deleteTableRequest: DeleteTableRequest): CompletableFuture<DeleteTableResponse> {
        return basis.deleteTable(deleteTableRequest)
    }

    override fun deleteTable(deleteTableRequest: Consumer<DeleteTableRequest.Builder>): CompletableFuture<DeleteTableResponse> {
        return basis.deleteTable(deleteTableRequest)
    }

    override fun describeBackup(describeBackupRequest: DescribeBackupRequest): CompletableFuture<DescribeBackupResponse> {
        return basis.describeBackup(describeBackupRequest)
    }

    override fun describeBackup(describeBackupRequest: Consumer<DescribeBackupRequest.Builder>): CompletableFuture<DescribeBackupResponse> {
        return basis.describeBackup(describeBackupRequest)
    }

    override fun describeContinuousBackups(describeContinuousBackupsRequest: DescribeContinuousBackupsRequest): CompletableFuture<DescribeContinuousBackupsResponse> {
        return basis.describeContinuousBackups(describeContinuousBackupsRequest)
    }

    override fun describeContinuousBackups(describeContinuousBackupsRequest: Consumer<DescribeContinuousBackupsRequest.Builder>): CompletableFuture<DescribeContinuousBackupsResponse> {
        return basis.describeContinuousBackups(describeContinuousBackupsRequest)
    }

    override fun describeContributorInsights(describeContributorInsightsRequest: DescribeContributorInsightsRequest): CompletableFuture<DescribeContributorInsightsResponse> {
        return basis.describeContributorInsights(describeContributorInsightsRequest)
    }

    override fun describeContributorInsights(describeContributorInsightsRequest: Consumer<DescribeContributorInsightsRequest.Builder>): CompletableFuture<DescribeContributorInsightsResponse> {
        return basis.describeContributorInsights(describeContributorInsightsRequest)
    }

    override fun describeEndpoints(describeEndpointsRequest: DescribeEndpointsRequest): CompletableFuture<DescribeEndpointsResponse> {
        return basis.describeEndpoints(describeEndpointsRequest)
    }

    override fun describeEndpoints(describeEndpointsRequest: Consumer<DescribeEndpointsRequest.Builder>): CompletableFuture<DescribeEndpointsResponse> {
        return basis.describeEndpoints(describeEndpointsRequest)
    }

    override fun describeEndpoints(): CompletableFuture<DescribeEndpointsResponse> {
        return basis.describeEndpoints()
    }

    override fun describeExport(describeExportRequest: DescribeExportRequest): CompletableFuture<DescribeExportResponse> {
        return basis.describeExport(describeExportRequest)
    }

    override fun describeExport(describeExportRequest: Consumer<DescribeExportRequest.Builder>): CompletableFuture<DescribeExportResponse> {
        return basis.describeExport(describeExportRequest)
    }

    override fun describeGlobalTable(describeGlobalTableRequest: DescribeGlobalTableRequest): CompletableFuture<DescribeGlobalTableResponse> {
        return basis.describeGlobalTable(describeGlobalTableRequest)
    }

    override fun describeGlobalTable(describeGlobalTableRequest: Consumer<DescribeGlobalTableRequest.Builder>): CompletableFuture<DescribeGlobalTableResponse> {
        return basis.describeGlobalTable(describeGlobalTableRequest)
    }

    override fun describeGlobalTableSettings(describeGlobalTableSettingsRequest: DescribeGlobalTableSettingsRequest): CompletableFuture<DescribeGlobalTableSettingsResponse> {
        return basis.describeGlobalTableSettings(describeGlobalTableSettingsRequest)
    }

    override fun describeGlobalTableSettings(describeGlobalTableSettingsRequest: Consumer<DescribeGlobalTableSettingsRequest.Builder>): CompletableFuture<DescribeGlobalTableSettingsResponse> {
        return basis.describeGlobalTableSettings(describeGlobalTableSettingsRequest)
    }

    override fun describeKinesisStreamingDestination(describeKinesisStreamingDestinationRequest: DescribeKinesisStreamingDestinationRequest): CompletableFuture<DescribeKinesisStreamingDestinationResponse> {
        return basis.describeKinesisStreamingDestination(describeKinesisStreamingDestinationRequest)
    }

    override fun describeKinesisStreamingDestination(describeKinesisStreamingDestinationRequest: Consumer<DescribeKinesisStreamingDestinationRequest.Builder>): CompletableFuture<DescribeKinesisStreamingDestinationResponse> {
        return basis.describeKinesisStreamingDestination(describeKinesisStreamingDestinationRequest)
    }

    override fun describeLimits(describeLimitsRequest: DescribeLimitsRequest): CompletableFuture<DescribeLimitsResponse> {
        return basis.describeLimits(describeLimitsRequest)
    }

    override fun describeLimits(describeLimitsRequest: Consumer<DescribeLimitsRequest.Builder>): CompletableFuture<DescribeLimitsResponse> {
        return basis.describeLimits(describeLimitsRequest)
    }

    override fun describeLimits(): CompletableFuture<DescribeLimitsResponse> {
        return basis.describeLimits()
    }

    override fun describeTable(describeTableRequest: DescribeTableRequest): CompletableFuture<DescribeTableResponse> {
        return basis.describeTable(describeTableRequest)
    }

    override fun describeTable(describeTableRequest: Consumer<DescribeTableRequest.Builder>): CompletableFuture<DescribeTableResponse> {
        return basis.describeTable(describeTableRequest)
    }

    override fun describeTableReplicaAutoScaling(describeTableReplicaAutoScalingRequest: DescribeTableReplicaAutoScalingRequest): CompletableFuture<DescribeTableReplicaAutoScalingResponse> {
        return basis.describeTableReplicaAutoScaling(describeTableReplicaAutoScalingRequest)
    }

    override fun describeTableReplicaAutoScaling(describeTableReplicaAutoScalingRequest: Consumer<DescribeTableReplicaAutoScalingRequest.Builder>): CompletableFuture<DescribeTableReplicaAutoScalingResponse> {
        return basis.describeTableReplicaAutoScaling(describeTableReplicaAutoScalingRequest)
    }

    override fun describeTimeToLive(describeTimeToLiveRequest: DescribeTimeToLiveRequest): CompletableFuture<DescribeTimeToLiveResponse> {
        return basis.describeTimeToLive(describeTimeToLiveRequest)
    }

    override fun describeTimeToLive(describeTimeToLiveRequest: Consumer<DescribeTimeToLiveRequest.Builder>): CompletableFuture<DescribeTimeToLiveResponse> {
        return basis.describeTimeToLive(describeTimeToLiveRequest)
    }

    override fun disableKinesisStreamingDestination(disableKinesisStreamingDestinationRequest: DisableKinesisStreamingDestinationRequest): CompletableFuture<DisableKinesisStreamingDestinationResponse> {
        return basis.disableKinesisStreamingDestination(disableKinesisStreamingDestinationRequest)
    }

    override fun disableKinesisStreamingDestination(disableKinesisStreamingDestinationRequest: Consumer<DisableKinesisStreamingDestinationRequest.Builder>): CompletableFuture<DisableKinesisStreamingDestinationResponse> {
        return basis.disableKinesisStreamingDestination(disableKinesisStreamingDestinationRequest)
    }

    override fun enableKinesisStreamingDestination(enableKinesisStreamingDestinationRequest: EnableKinesisStreamingDestinationRequest): CompletableFuture<EnableKinesisStreamingDestinationResponse> {
        return basis.enableKinesisStreamingDestination(enableKinesisStreamingDestinationRequest)
    }

    override fun enableKinesisStreamingDestination(enableKinesisStreamingDestinationRequest: Consumer<EnableKinesisStreamingDestinationRequest.Builder>): CompletableFuture<EnableKinesisStreamingDestinationResponse> {
        return basis.enableKinesisStreamingDestination(enableKinesisStreamingDestinationRequest)
    }

    override fun executeStatement(executeStatementRequest: ExecuteStatementRequest): CompletableFuture<ExecuteStatementResponse> {
        return basis.executeStatement(executeStatementRequest)
    }

    override fun executeStatement(executeStatementRequest: Consumer<ExecuteStatementRequest.Builder>): CompletableFuture<ExecuteStatementResponse> {
        return basis.executeStatement(executeStatementRequest)
    }

    override fun executeTransaction(executeTransactionRequest: ExecuteTransactionRequest): CompletableFuture<ExecuteTransactionResponse> {
        return basis.executeTransaction(executeTransactionRequest)
    }

    override fun executeTransaction(executeTransactionRequest: Consumer<ExecuteTransactionRequest.Builder>): CompletableFuture<ExecuteTransactionResponse> {
        return basis.executeTransaction(executeTransactionRequest)
    }

    override fun exportTableToPointInTime(exportTableToPointInTimeRequest: ExportTableToPointInTimeRequest): CompletableFuture<ExportTableToPointInTimeResponse> {
        return basis.exportTableToPointInTime(exportTableToPointInTimeRequest)
    }

    override fun exportTableToPointInTime(exportTableToPointInTimeRequest: Consumer<ExportTableToPointInTimeRequest.Builder>): CompletableFuture<ExportTableToPointInTimeResponse> {
        return basis.exportTableToPointInTime(exportTableToPointInTimeRequest)
    }

    override fun getItem(getItemRequest: GetItemRequest): CompletableFuture<GetItemResponse> {
        return basis.getItem(getItemRequest)
    }

    override fun getItem(getItemRequest: Consumer<GetItemRequest.Builder>): CompletableFuture<GetItemResponse> {
        return basis.getItem(getItemRequest)
    }

    override fun listBackups(listBackupsRequest: ListBackupsRequest): CompletableFuture<ListBackupsResponse> {
        return basis.listBackups(listBackupsRequest)
    }

    override fun listBackups(listBackupsRequest: Consumer<ListBackupsRequest.Builder>): CompletableFuture<ListBackupsResponse> {
        return basis.listBackups(listBackupsRequest)
    }

    override fun listBackups(): CompletableFuture<ListBackupsResponse> {
        return basis.listBackups()
    }

    override fun listContributorInsights(listContributorInsightsRequest: ListContributorInsightsRequest): CompletableFuture<ListContributorInsightsResponse> {
        return basis.listContributorInsights(listContributorInsightsRequest)
    }

    override fun listContributorInsights(listContributorInsightsRequest: Consumer<ListContributorInsightsRequest.Builder>): CompletableFuture<ListContributorInsightsResponse> {
        return basis.listContributorInsights(listContributorInsightsRequest)
    }

    override fun listContributorInsightsPaginator(listContributorInsightsRequest: ListContributorInsightsRequest): ListContributorInsightsPublisher {
        return basis.listContributorInsightsPaginator(listContributorInsightsRequest)
    }

    override fun listContributorInsightsPaginator(listContributorInsightsRequest: Consumer<ListContributorInsightsRequest.Builder>): ListContributorInsightsPublisher {
        return basis.listContributorInsightsPaginator(listContributorInsightsRequest)
    }

    override fun listExports(listExportsRequest: ListExportsRequest): CompletableFuture<ListExportsResponse> {
        return basis.listExports(listExportsRequest)
    }

    override fun listExports(listExportsRequest: Consumer<ListExportsRequest.Builder>): CompletableFuture<ListExportsResponse> {
        return basis.listExports(listExportsRequest)
    }

    override fun listExportsPaginator(listExportsRequest: ListExportsRequest): ListExportsPublisher {
        return basis.listExportsPaginator(listExportsRequest)
    }

    override fun listExportsPaginator(listExportsRequest: Consumer<ListExportsRequest.Builder>): ListExportsPublisher {
        return basis.listExportsPaginator(listExportsRequest)
    }

    override fun listGlobalTables(listGlobalTablesRequest: ListGlobalTablesRequest): CompletableFuture<ListGlobalTablesResponse> {
        return basis.listGlobalTables(listGlobalTablesRequest)
    }

    override fun listGlobalTables(listGlobalTablesRequest: Consumer<ListGlobalTablesRequest.Builder>): CompletableFuture<ListGlobalTablesResponse> {
        return basis.listGlobalTables(listGlobalTablesRequest)
    }

    override fun listGlobalTables(): CompletableFuture<ListGlobalTablesResponse> {
        return basis.listGlobalTables()
    }

    override fun listTables(listTablesRequest: ListTablesRequest): CompletableFuture<ListTablesResponse> {
        return basis.listTables(listTablesRequest)
    }

    override fun listTables(listTablesRequest: Consumer<ListTablesRequest.Builder>): CompletableFuture<ListTablesResponse> {
        return basis.listTables(listTablesRequest)
    }

    override fun listTables(): CompletableFuture<ListTablesResponse> {
        return basis.listTables()
    }

    override fun listTablesPaginator(): ListTablesPublisher {
        return basis.listTablesPaginator()
    }

    override fun listTablesPaginator(listTablesRequest: ListTablesRequest): ListTablesPublisher {
        return basis.listTablesPaginator(listTablesRequest)
    }

    override fun listTablesPaginator(listTablesRequest: Consumer<ListTablesRequest.Builder>): ListTablesPublisher {
        return basis.listTablesPaginator(listTablesRequest)
    }

    override fun listTagsOfResource(listTagsOfResourceRequest: ListTagsOfResourceRequest): CompletableFuture<ListTagsOfResourceResponse> {
        return basis.listTagsOfResource(listTagsOfResourceRequest)
    }

    override fun listTagsOfResource(listTagsOfResourceRequest: Consumer<ListTagsOfResourceRequest.Builder>): CompletableFuture<ListTagsOfResourceResponse> {
        return basis.listTagsOfResource(listTagsOfResourceRequest)
    }

    override fun putItem(putItemRequest: PutItemRequest): CompletableFuture<PutItemResponse> {
        return basis.putItem(putItemRequest)
    }

    override fun putItem(putItemRequest: Consumer<PutItemRequest.Builder>): CompletableFuture<PutItemResponse> {
        return basis.putItem(putItemRequest)
    }

    override fun query(queryRequest: QueryRequest): CompletableFuture<QueryResponse> {
        return basis.query(queryRequest)
    }

    override fun query(queryRequest: Consumer<QueryRequest.Builder>): CompletableFuture<QueryResponse> {
        return basis.query(queryRequest)
    }

    override fun queryPaginator(queryRequest: QueryRequest): QueryPublisher {
        return basis.queryPaginator(queryRequest)
    }

    override fun queryPaginator(queryRequest: Consumer<QueryRequest.Builder>): QueryPublisher {
        return basis.queryPaginator(queryRequest)
    }

    override fun restoreTableFromBackup(restoreTableFromBackupRequest: RestoreTableFromBackupRequest): CompletableFuture<RestoreTableFromBackupResponse> {
        return basis.restoreTableFromBackup(restoreTableFromBackupRequest)
    }

    override fun restoreTableFromBackup(restoreTableFromBackupRequest: Consumer<RestoreTableFromBackupRequest.Builder>): CompletableFuture<RestoreTableFromBackupResponse> {
        return basis.restoreTableFromBackup(restoreTableFromBackupRequest)
    }

    override fun restoreTableToPointInTime(restoreTableToPointInTimeRequest: RestoreTableToPointInTimeRequest): CompletableFuture<RestoreTableToPointInTimeResponse> {
        return basis.restoreTableToPointInTime(restoreTableToPointInTimeRequest)
    }

    override fun restoreTableToPointInTime(restoreTableToPointInTimeRequest: Consumer<RestoreTableToPointInTimeRequest.Builder>): CompletableFuture<RestoreTableToPointInTimeResponse> {
        return basis.restoreTableToPointInTime(restoreTableToPointInTimeRequest)
    }

    override fun scan(scanRequest: ScanRequest): CompletableFuture<ScanResponse> {
        return basis.scan(scanRequest)
    }

    override fun scan(scanRequest: Consumer<ScanRequest.Builder>): CompletableFuture<ScanResponse> {
        return basis.scan(scanRequest)
    }

    override fun scanPaginator(scanRequest: ScanRequest): ScanPublisher {
        return basis.scanPaginator(scanRequest)
    }

    override fun scanPaginator(scanRequest: Consumer<ScanRequest.Builder>): ScanPublisher {
        return basis.scanPaginator(scanRequest)
    }

    override fun tagResource(tagResourceRequest: TagResourceRequest): CompletableFuture<TagResourceResponse> {
        return basis.tagResource(tagResourceRequest)
    }

    override fun tagResource(tagResourceRequest: Consumer<TagResourceRequest.Builder>): CompletableFuture<TagResourceResponse> {
        return basis.tagResource(tagResourceRequest)
    }

    override fun transactGetItems(transactGetItemsRequest: TransactGetItemsRequest): CompletableFuture<TransactGetItemsResponse> {
        return basis.transactGetItems(transactGetItemsRequest)
    }

    override fun transactGetItems(transactGetItemsRequest: Consumer<TransactGetItemsRequest.Builder>): CompletableFuture<TransactGetItemsResponse> {
        return basis.transactGetItems(transactGetItemsRequest)
    }

    override fun transactWriteItems(transactWriteItemsRequest: TransactWriteItemsRequest): CompletableFuture<TransactWriteItemsResponse> {
        return basis.transactWriteItems(transactWriteItemsRequest)
    }

    override fun transactWriteItems(transactWriteItemsRequest: Consumer<TransactWriteItemsRequest.Builder>): CompletableFuture<TransactWriteItemsResponse> {
        return basis.transactWriteItems(transactWriteItemsRequest)
    }

    override fun untagResource(untagResourceRequest: UntagResourceRequest): CompletableFuture<UntagResourceResponse> {
        return basis.untagResource(untagResourceRequest)
    }

    override fun untagResource(untagResourceRequest: Consumer<UntagResourceRequest.Builder>): CompletableFuture<UntagResourceResponse> {
        return basis.untagResource(untagResourceRequest)
    }

    override fun updateContinuousBackups(updateContinuousBackupsRequest: UpdateContinuousBackupsRequest): CompletableFuture<UpdateContinuousBackupsResponse> {
        return basis.updateContinuousBackups(updateContinuousBackupsRequest)
    }

    override fun updateContinuousBackups(updateContinuousBackupsRequest: Consumer<UpdateContinuousBackupsRequest.Builder>): CompletableFuture<UpdateContinuousBackupsResponse> {
        return basis.updateContinuousBackups(updateContinuousBackupsRequest)
    }

    override fun updateContributorInsights(updateContributorInsightsRequest: UpdateContributorInsightsRequest): CompletableFuture<UpdateContributorInsightsResponse> {
        return basis.updateContributorInsights(updateContributorInsightsRequest)
    }

    override fun updateContributorInsights(updateContributorInsightsRequest: Consumer<UpdateContributorInsightsRequest.Builder>): CompletableFuture<UpdateContributorInsightsResponse> {
        return basis.updateContributorInsights(updateContributorInsightsRequest)
    }

    override fun updateGlobalTable(updateGlobalTableRequest: UpdateGlobalTableRequest): CompletableFuture<UpdateGlobalTableResponse> {
        return basis.updateGlobalTable(updateGlobalTableRequest)
    }

    override fun updateGlobalTable(updateGlobalTableRequest: Consumer<UpdateGlobalTableRequest.Builder>): CompletableFuture<UpdateGlobalTableResponse> {
        return basis.updateGlobalTable(updateGlobalTableRequest)
    }

    override fun updateGlobalTableSettings(updateGlobalTableSettingsRequest: UpdateGlobalTableSettingsRequest): CompletableFuture<UpdateGlobalTableSettingsResponse> {
        return basis.updateGlobalTableSettings(updateGlobalTableSettingsRequest)
    }

    override fun updateGlobalTableSettings(updateGlobalTableSettingsRequest: Consumer<UpdateGlobalTableSettingsRequest.Builder>): CompletableFuture<UpdateGlobalTableSettingsResponse> {
        return basis.updateGlobalTableSettings(updateGlobalTableSettingsRequest)
    }

    override fun updateItem(updateItemRequest: UpdateItemRequest): CompletableFuture<UpdateItemResponse> {
        return basis.updateItem(updateItemRequest)
    }

    override fun updateItem(updateItemRequest: Consumer<UpdateItemRequest.Builder>): CompletableFuture<UpdateItemResponse> {
        return basis.updateItem(updateItemRequest)
    }

    override fun updateTable(updateTableRequest: UpdateTableRequest): CompletableFuture<UpdateTableResponse> {
        return basis.updateTable(updateTableRequest)
    }

    override fun updateTable(updateTableRequest: Consumer<UpdateTableRequest.Builder>): CompletableFuture<UpdateTableResponse> {
        return basis.updateTable(updateTableRequest)
    }

    override fun updateTableReplicaAutoScaling(updateTableReplicaAutoScalingRequest: UpdateTableReplicaAutoScalingRequest): CompletableFuture<UpdateTableReplicaAutoScalingResponse> {
        return basis.updateTableReplicaAutoScaling(updateTableReplicaAutoScalingRequest)
    }

    override fun updateTableReplicaAutoScaling(updateTableReplicaAutoScalingRequest: Consumer<UpdateTableReplicaAutoScalingRequest.Builder>): CompletableFuture<UpdateTableReplicaAutoScalingResponse> {
        return basis.updateTableReplicaAutoScaling(updateTableReplicaAutoScalingRequest)
    }

    override fun updateTimeToLive(updateTimeToLiveRequest: UpdateTimeToLiveRequest): CompletableFuture<UpdateTimeToLiveResponse> {
        return basis.updateTimeToLive(updateTimeToLiveRequest)
    }

    override fun updateTimeToLive(updateTimeToLiveRequest: Consumer<UpdateTimeToLiveRequest.Builder>): CompletableFuture<UpdateTimeToLiveResponse> {
        return basis.updateTimeToLive(updateTimeToLiveRequest)
    }

    override fun waiter(): DynamoDbAsyncWaiter {
        return basis.waiter()
    }

    override fun serviceName(): String {
        return basis.serviceName()
    }

    override fun close() {
        basis.close()
    }

    companion object {
        fun create(): DynamoDbAsyncClient {
            return DynamoDbAsyncClient.create()
        }

        fun builder(): DynamoDbAsyncClientBuilder {
            return DynamoDbAsyncClient.builder()
        }
    }
}