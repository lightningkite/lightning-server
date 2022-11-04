import { Condition } from "./Condition";
import { Modification } from "./Modification";
import { Query, MassModification, EntryChange, GroupCountQuery, AggregateQuery, GroupAggregateQuery } from "./otherModels";
import { HasId } from "./sessionRest";
export declare function mockRestEndpointFunctions<T extends HasId>(items: T[], label: string): {
    query(input: Query<T>, requesterToken: string): Promise<Array<T>>;
    detail(id: string, requesterToken: string): Promise<T>;
    insertBulk(input: Array<T>, requesterToken: string): Promise<Array<T>>;
    insert(input: T, requesterToken: string): Promise<T>;
    upsert(id: string, input: T, requesterToken: string): Promise<T>;
    bulkReplace(input: Array<T>, requesterToken: string): Promise<Array<T>>;
    replace(id: string, input: T, requesterToken: string): Promise<T>;
    bulkModify(input: MassModification<T>, requesterToken: string): Promise<number>;
    modifyWithDiff(id: string, input: Modification<T>, requesterToken: string): Promise<EntryChange<T>>;
    modify(id: string, input: Modification<T>, requesterToken: string): Promise<T>;
    bulkDelete(input: Condition<T>, requesterToken: string): Promise<number>;
    delete(id: string, requesterToken: string): Promise<void>;
    count(input: Condition<T>, requesterToken: string): Promise<number>;
    groupCount(input: GroupCountQuery<T>, requesterToken: string): Promise<Record<string, number>>;
    aggregate(input: AggregateQuery<T>, requesterToken: string): Promise<number>;
    groupAggregate(input: GroupAggregateQuery<T>, requesterToken: string): Promise<Record<string, number>>;
};
