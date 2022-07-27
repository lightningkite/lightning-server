import { Condition } from './Condition';
import { Modification } from './Modification';
export interface Query<T> {
    condition?: Condition<T>;
    orderBy?: Array<keyof T>;
    skip?: number;
    limit?: number;
}
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
