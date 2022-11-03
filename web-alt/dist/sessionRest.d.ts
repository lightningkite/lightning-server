import { Condition } from "./Condition";
import { Modification } from "./Modification";
import { Query, MassModification, EntryChange, GroupCountQuery, AggregateQuery } from "./otherModels";
export interface HasId {
    _id: string;
}
export interface SessionRestEndpoint<T extends HasId> {
    query(input: Query<T>): Promise<Array<T>>;
    detail(id: string): Promise<T>;
    insertBulk(input: Array<T>): Promise<Array<T>>;
    insert(input: T): Promise<T>;
    upsert(id: string, input: T): Promise<T>;
    bulkReplace(input: Array<T>): Promise<Array<T>>;
    replace(id: string, input: T): Promise<T>;
    bulkModify(input: MassModification<T>): Promise<number>;
    modifyWithDiff(id: string, input: Modification<T>): Promise<EntryChange<T>>;
    modify(id: string, input: Modification<T>): Promise<T>;
    bulkDelete(input: Condition<T>): Promise<number>;
    delete(id: string): Promise<void>;
    count(input: Condition<T>): Promise<number>;
    groupCount(input: GroupCountQuery<T>): Promise<Record<string, number>>;
    aggregate(input: AggregateQuery<T>): Promise<number | null | undefined>;
    groupAggregate(input: AggregateQuery<T>): Promise<Record<string, number | null | undefined>>;
}
