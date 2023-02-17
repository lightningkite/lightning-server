// Package: com.lightningkite.lightningdb
// Generated by Khrysalis - this file will be overwritten.
import {Condition} from './Condition'
import {SortPart} from './SortPart'
import {PropChain, startChain} from './dsl'
import {ReifiedType, setUpDataClass} from '@lightningkite/khrysalis-runtime'

//! Declares com.lightningkite.lightningdb.Query
export class Query<T extends any> {
    public constructor(public readonly condition: Condition<T> = new Condition.Always<T>(), public readonly orderBy: Array<SortPart<T>> = [], public readonly skip: number = 0, public readonly limit: number = 100) {
    }

    public static properties = ["condition", "orderBy", "skip", "limit"]

    public static propertyTypes(T: ReifiedType) {
        return {condition: [Condition, T], orderBy: [Array, [SortPart, T]], skip: [Number], limit: [Number]}
    }

    copy: (values: Partial<Query<T>>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;

    public static constructorListcomSortPartcomQueryTIntIntFunction1comPropChaincomQueryTQueryTConditioncomQueryT<T extends any>(
        orderBy: Array<SortPart<T>> = [],
        skip: number = 0,
        limit: number = 100,
        makeCondition: ((a: PropChain<T, T>) => Condition<T>),
    ) {
        let result = new Query<T>(makeCondition(startChain<T>()), orderBy, skip, limit);

        return result;
    }
}

setUpDataClass(Query)