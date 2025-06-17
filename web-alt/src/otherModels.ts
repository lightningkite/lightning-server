import { Condition } from './Condition'
import { Modification } from './Modification'

/**
 * condition: Defaults to Condition.Always<T>()
 * skip: Defaults to 0
 * limit: Defaults to 100
 */
export interface Query<T> {
    condition?: Condition<T>
    orderBy?: Array<SortPart<T>>
    skip?: number
    limit?: number
}

/**
 * condition: Defaults to Condition.Always<T>()
 * skip: Defaults to 0
 * limit: Defaults to 100
 */
export interface QueryPartial<T> {
    fields: Array<DataClassPathPartial<T>>
    condition?: Condition<T>
    orderBy?: Array<SortPart<T>>
    skip?: number
    limit?: number
}

export type SortPart<T> = (keyof T & string) | `-${keyof T& string}` | `~${keyof T & string}` | `-~${keyof T & string}`

// To replace Sort Part
export type SortPart1<T> = ObjectPath<T> | `-${ObjectPath<T>}` | `~${ObjectPath<T>}` | `-~${ObjectPath<T>}`

export type ObjectPath<T> = T extends object
  ? {
      [K in keyof T]: `${Exclude<K, symbol>}${`.${ObjectPath<T[K]>}` | ""}`;
    }[keyof T]
  : never;

export interface MassModification<T> {
    condition: Condition<T>
    modification: Modification<T>
}
export interface EntryChange<T> {
    old?: T | null
    new?: T | null
}
export interface ListChange<T> {
    wholeList?: Array<T> | null
    old?: T | null
    new?: T | null
}
export interface GroupCountQuery<Model>{
    condition?: Condition<Model>
    groupBy: keyof Model
}

export interface AggregateQuery<Model>{
    aggregate: Aggregate,
    condition?: Condition<Model>
    property: keyof Model
}

export interface GroupAggregateQuery<Model>{
    aggregate: Aggregate,
    condition?: Condition<Model>
    groupBy: keyof Model,
    property: keyof Model
}

export enum Aggregate {
    Sum = "Sum",
    Average = "Average",
    StandardDeviationSample = "StandardDeviationSample",
    StandardDeviationPopulation = "StandardDeviationPopulation",
}

export type DeepPartial<T> = {
    [P in keyof T]?: (T[P] extends object ? DeepPartial<T[P]> : T[P]);
};

export type DataClassPath<T> = keyof T
export type DataClassPathPartial<T> = keyof T
