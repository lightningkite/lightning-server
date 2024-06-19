import { Condition } from './Condition';
import { Modification } from './Modification';
export interface Query<T> {
    condition?: Condition<T>;
    orderBy?: Array<SortPart<T>>;
    skip?: number;
    limit?: number;
}
export interface QueryPartial<T> {
    fields: Array<DataClassPathPartial<T>>;
    condition?: Condition<T>;
    orderBy?: Array<SortPart<T>>;
    skip?: number;
    limit?: number;
}
export declare type SortPart<T> = (keyof T & string) | `-${keyof T & string}` | `~${keyof T & string}` | `-~${keyof T & string}`;
export interface MassModification<T> {
    condition: Condition<T>;
    modification: Modification<T>;
}
export interface EntryChange<T> {
    old?: T | null;
    new?: T | null;
}
export interface ListChange<T> {
    wholeList?: Array<T> | null;
    old?: T | null;
    new?: T | null;
}
export interface GroupCountQuery<Model> {
    condition?: Condition<Model>;
    groupBy: keyof Model;
}
export interface AggregateQuery<Model> {
    aggregate: Aggregate;
    condition?: Condition<Model>;
    property: keyof Model;
}
export interface GroupAggregateQuery<Model> {
    aggregate: Aggregate;
    condition?: Condition<Model>;
    groupBy: keyof Model;
    property: keyof Model;
}
export declare enum Aggregate {
    Sum = "Sum",
    Average = "Average",
    StandardDeviationSample = "StandardDeviationSample",
    StandardDeviationPopulation = "StandardDeviationPopulation"
}
export declare type DeepPartial<T> = {
    [P in keyof T]?: (T[P] extends object ? DeepPartial<T[P]> : T[P]);
};
export declare type DataClassPath<T> = keyof T;
export declare type DataClassPathPartial<T> = keyof T;
