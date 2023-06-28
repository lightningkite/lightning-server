import { Condition } from './Condition';
import { DataClassPath } from './DataClassPath';
import { Modification } from './Modification';
import { SortPart } from './SortPart';
import { ReifiedType } from '@lightningkite/khrysalis-runtime';
export declare class Query<T extends any> {
    readonly condition: Condition<T>;
    readonly orderBy: Array<SortPart<T>>;
    readonly skip: number;
    readonly limit: number;
    readonly skipFieldsMask: (Modification<T> | null);
    constructor(condition?: Condition<T>, orderBy?: Array<SortPart<T>>, skip?: number, limit?: number, skipFieldsMask?: (Modification<T> | null));
    static properties: string[];
    static propertyTypes(T: ReifiedType): {
        condition: (ReifiedType<unknown> | typeof Condition)[];
        orderBy: (ArrayConstructor | (ReifiedType<unknown> | typeof SortPart)[])[];
        skip: NumberConstructor[];
        limit: NumberConstructor[];
        skipFieldsMask: (ReifiedType<unknown> | typeof Modification)[];
    };
    copy: (values: Partial<Query<T>>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
    static constructorListcomSortPartcomQueryTIntIntFunction1comDataClassPathcomQueryTQueryTConditioncomQueryT<T extends any>(orderBy: SortPart<T>[] | undefined, skip: number | undefined, limit: number | undefined, makeCondition: ((a: DataClassPath<T, T>) => Condition<T>)): Query<T>;
}
