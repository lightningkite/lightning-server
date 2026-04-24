package com.lightningkite.lightningserver.typed

import com.lightningkite.services.database.*

/**
 * Client-side interface for a complete REST CRUD API for a model type.
 *
 * Provides standard database operations (query, insert, update, delete, aggregate) over HTTP.
 * This interface is typically generated for each model exposed via [ModelRestEndpoints] on the server.
 *
 * @param T The model type being managed
 * @param ID The type of the model's ID field
 */
@LiveVersion(LiveClientModelRestEndpoints::class)
public interface ClientModelRestEndpoints<T : HasId<ID>, ID : Comparable<ID>> {
    /**
     * Gets a default instance of the model with all required fields populated.
     * Useful for UI forms and understanding required fields.
     *
     * Default implementation throws IllegalArgumentException if not overridden by server.
     */
    public suspend fun default(): T = throw IllegalArgumentException()

    /**
     * Queries for models matching the given criteria.
     *
     * @param input Query with optional filter, ordering, skip, and limit
     * @return List of matching models
     */
    public suspend fun query(input: Query<T>): List<T>

    /**
     * Queries for partial models (only specified fields) matching the given criteria.
     *
     * More efficient than full queries when you only need specific fields.
     *
     * @param input Query specifying which fields to retrieve
     * @return List of partial models with only requested fields populated
     */
    public suspend fun queryPartial(input: QueryPartial<T>): List<Partial<T>>

    /**
     * Gets a single model by ID.
     *
     * @param id The model's ID
     * @return The model
     * @throws NotFoundException if no model exists with that ID
     */
    public suspend fun detail(id: ID): T

    /**
     * Inserts multiple new models in a single request.
     *
     * @param input List of models to insert
     * @return List of inserted models (may have server-assigned IDs or timestamps)
     * @throws BadRequestException if any model violates constraints
     */
    public suspend fun insertBulk(input: List<T>): List<T>

    /**
     * Inserts a single new model.
     *
     * @param input Model to insert
     * @return The inserted model (may have server-assigned ID or timestamps)
     * @throws BadRequestException if the model violates constraints
     */
    public suspend fun insert(input: T): T

    /**
     * Upserts a model (insert if not exists, update if exists) by ID.
     *
     * @param id The model's ID
     * @param input The model data
     * @return The upserted model
     * @throws BadRequestException if the model violates constraints
     */
    public suspend fun upsert(id: ID, input: T): T

    /**
     * Replaces multiple models by ID in a single request.
     *
     * @param input List of models to replace (matched by their _id field)
     * @return List of replaced models
     * @throws NotFoundException if any model doesn't exist
     */
    public suspend fun bulkReplace(input: List<T>): List<T>

    /**
     * Replaces a model by ID (must already exist).
     *
     * @param id The model's ID
     * @param input The new model data
     * @return The replaced model
     * @throws NotFoundException if the model doesn't exist
     */
    public suspend fun replace(id: ID, input: T): T

    /**
     * Modifies multiple models matching a condition with the given modifications.
     *
     * @param input Mass modification with condition and modifications to apply
     * @return Number of models modified
     */
    public suspend fun bulkModify(input: MassModification<T>): Int

    /**
     * Modifies a single model by ID, returning both old and new values.
     *
     * @param id The model's ID
     * @param input Modifications to apply
     * @return EntryChange containing old and new values
     * @throws NotFoundException if the model doesn't exist
     */
    public suspend fun modifyWithDiff(id: ID, input: Modification<T>): EntryChange<T>

    /**
     * Modifies a single model by ID, returning the new value.
     *
     * @param id The model's ID
     * @param input Modifications to apply
     * @return The modified model
     * @throws NotFoundException if the model doesn't exist
     */
    public suspend fun modify(id: ID, input: Modification<T>): T

    /**
     * Deletes all models matching a condition.
     *
     * @param input Condition for models to delete
     * @return Number of models deleted
     */
    public suspend fun bulkDelete(input: Condition<T>): Int

    /**
     * Deletes a single model by ID.
     *
     * @param id The model's ID
     * @throws NotFoundException if the model doesn't exist
     */
    public suspend fun delete(id: ID): Unit

    /**
     * Counts models matching a condition.
     *
     * @param input Condition to filter by
     * @return Number of matching models
     */
    public suspend fun count(input: Condition<T>): Int

    /**
     * Counts models grouped by a field's value, with toString() applied to keys.
     *
     * @param input Group count query specifying condition and grouping field
     * @return Map from group key (as string) to count
     */
    public suspend fun groupCount(input: GroupCountQuery<T>): Map<String, Int>

    /**
     * Counts models grouped by a field's value, with JSON serialization of keys.
     *
     * Preferred over [groupCount] for complex key types (dates, objects, etc.).
     *
     * @param input Group count query specifying condition and grouping field
     * @return Map from group key (JSON-encoded) to count
     */
    public suspend fun groupCount2(input: GroupCountQuery<T>): Map<String, Int> = groupCount(input)

    /**
     * Aggregates a numeric property of models matching a condition.
     *
     * @param input Aggregate query specifying operation, condition, and property
     * @return Aggregated value (null if no matching models)
     */
    public suspend fun aggregate(input: AggregateQuery<T>): Double?

    /**
     * Aggregates a numeric property grouped by field values, with toString() applied to keys.
     *
     * @param input Group aggregate query specifying operation, condition, grouping, and property
     * @return Map from group key (as string) to aggregated value
     */
    public suspend fun groupAggregate(input: GroupAggregateQuery<T>): Map<String, Double?>

    /**
     * Aggregates a numeric property grouped by field values, with JSON serialization of keys.
     *
     * Preferred over [groupAggregate] for complex key types.
     *
     * @param input Group aggregate query specifying operation, condition, grouping, and property
     * @return Map from group key (JSON-encoded) to aggregated value
     */
    public suspend fun groupAggregate2(input: GroupAggregateQuery<T>): Map<String, Double?> = groupAggregate(input)

    /**
     * Gets the current user's permissions for this model collection.
     *
     * @return Permissions object indicating which operations are allowed
     */
    public suspend fun permissions(): ModelPermissions<T>
}

/**
 * Client-side interface for real-time WebSocket updates for a model collection.
 *
 * Allows subscribing to changes (inserts, updates, deletes) in a model collection
 * matching specific conditions.
 *
 * @param T The model type being watched
 * @param ID The type of the model's ID field
 */
@LiveVersion(LiveClientModelRestUpdatesWebsocket::class)
public interface ClientModelRestUpdatesWebsocket<T : HasId<ID>, ID : Comparable<ID>> {
    /**
     * Creates a WebSocket for receiving real-time collection updates.
     *
     * Send [Condition] messages to specify what models to watch.
     * Receive [CollectionUpdates] messages containing model changes.
     *
     * @return WebSocket for subscribing to and receiving updates
     */
    public fun updates(): ClientWebSocket<Condition<T>, CollectionUpdates<T, ID>>
}

/**
 * Combined interface providing both REST CRUD operations and WebSocket updates.
 *
 * This is the most complete client interface for a model, offering both request-response
 * operations and real-time subscriptions.
 *
 * @param T The model type being managed
 * @param ID The type of the model's ID field
 */
@LiveVersion(LiveClientModelRestEndpointsAndUpdatesWebsocket::class)
public interface ClientModelRestEndpointsAndUpdatesWebsocket<T : HasId<ID>, ID : Comparable<ID>> :
    ClientModelRestEndpoints<T, ID>, ClientModelRestUpdatesWebsocket<T, ID>

/*
 * TODO: API Improvements
 *
 * 1. Consider adding cursor-based pagination support in addition to skip/limit
 * 2. Add optimistic locking support via ETags or version fields to prevent lost updates
 * 3. Consider providing batch variants that return partial success information (which succeeded, which failed)
 * 4. The default() method could accept parameters to customize the default instance
 * 5. Add support for partial updates via JSON Patch or similar standard
 * 6. Consider adding a watch() method for long-polling as an alternative to WebSocket updates
 * 7. Aggregate methods could support multiple aggregations in one request for efficiency
 * 8. Add transaction support for bulk operations that should be atomic
 */