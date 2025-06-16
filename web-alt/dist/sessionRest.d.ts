import { Condition } from "./Condition";
import { Modification } from "./Modification";
import { Query, MassModification, EntryChange, GroupCountQuery, AggregateQuery, DeepPartial, QueryPartial, GroupAggregateQuery } from "./otherModels";
declare type UUID = string;
export interface HasId {
    _id: UUID;
}
export interface SessionRestEndpoint<T extends HasId> {
    /**
    * Gets a default item that would be useful to start creating a full one to insert.  Primarily used for administrative interfaces.
    **/
    default: () => Promise<T>;
    /**
    * Gets a list of items that match the given query.
    **/
    query(input: Query<T>): Promise<Array<T>>;
    /**
    * Gets parts of items that match the given query.
    **/
    queryPartial<Q extends QueryPartial<T>>(input: Q): Promise<Array<{
        [K in keyof T as K extends Q["fields"][number] ? K : never]: T[K];
    }>>;
    /**
    * Gets a single item by ID.
    **/
    detail(id: UUID): Promise<T>;
    /**
    * Creates multiple items at the same time.
    **/
    insertBulk(input: Array<T>): Promise<Array<T>>;
    /**
    * Creates a new item
    **/
    insert(input: T): Promise<T>;
    /**
    * Creates or updates a item
    **/
    upsert(id: UUID, input: T): Promise<T>;
    /**
    * Modifies many items at the same time by ID.
    **/
    bulkReplace(input: Array<T>): Promise<Array<T>>;
    /**
    * Replaces a single item by ID.
    **/
    replace(id: UUID, input: T): Promise<T>;
    /**
    * Modifies many items at the same time.  Returns the number of changed items.
    **/
    bulkModify(input: MassModification<T>): Promise<number>;
    /**
    * Modifies a item by ID, returning both the previous value and new value.
    **/
    modifyWithDiff(id: UUID, input: Modification<T>): Promise<EntryChange<T>>;
    /**
    * Modifies a item by ID, returning the new value.
    **/
    modify(id: UUID, input: Modification<T>): Promise<T>;
    /**
    * Modifies a item by ID, returning the new value.
    **/
    simplifiedModify(id: UUID, input: DeepPartial<T>): Promise<T>;
    /**
    * Deletes all matching items, returning the number of deleted items.
    **/
    bulkDelete(input: Condition<T>): Promise<number>;
    /**
    * Deletes a item by id.
    **/
    delete(id: UUID): Promise<void>;
    /**
    * Gets the total number of items matching the given condition.
    **/
    count(input: Condition<T>): Promise<number>;
    /**
    * Gets the total number of items matching the given condition divided by group.
    **/
    groupCount(input: GroupCountQuery<T>): Promise<Record<string, number>>;
    /**
   * Aggregates a property of items matching the given condition.
   **/
    aggregate(input: AggregateQuery<T>): Promise<number | null | undefined>;
    /**
    * Aggregates a property of items matching the given condition divided by group.
    **/
    groupAggregate(input: GroupAggregateQuery<T>): Promise<Record<string, number | null | undefined>>;
}
/**
 * Session rest endpoints with an auth token as the last parameter
 */
export declare type ApiRestEndpoint<T extends HasId> = {
    default: (token: string) => Promise<T>;
    query(input: Query<T>, token: string): Promise<Array<T>>;
    queryPartial<Q extends QueryPartial<T>>(input: Q, token: string): Promise<Array<{
        [K in keyof T as K extends Q["fields"][number] ? K : never]: T[K];
    }>>;
    detail(id: UUID, token: string): Promise<T>;
    insertBulk(input: Array<T>, token: string): Promise<Array<T>>;
    insert(input: T, token: string): Promise<T>;
    upsert(id: UUID, input: T, token: string): Promise<T>;
    bulkReplace(input: Array<T>, token: string): Promise<Array<T>>;
    replace(id: UUID, input: T, token: string): Promise<T>;
    bulkModify(input: MassModification<T>, token: string): Promise<number>;
    modifyWithDiff(id: UUID, input: Modification<T>, token: string): Promise<EntryChange<T>>;
    modify(id: UUID, input: Modification<T>, token: string): Promise<T>;
    simplifiedModify(id: UUID, input: DeepPartial<T>, token: string): Promise<T>;
    bulkDelete(input: Condition<T>, token: string): Promise<number>;
    delete(id: UUID, token: string): Promise<void>;
    count(input: Condition<T>, token: string): Promise<number>;
    groupCount(input: GroupCountQuery<T>, token: string): Promise<Record<string, number>>;
    aggregate(input: AggregateQuery<T>, token: string): Promise<number | null | undefined>;
    groupAggregate(input: GroupAggregateQuery<T>, token: string): Promise<Record<string, number | null | undefined>>;
};
export {};
