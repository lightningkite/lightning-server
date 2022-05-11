import { Condition } from './Condition';
import { SortPart } from './SortPart';
import { PropChain } from './dsl';
import { ReifiedType } from '@lightningkite/khrysalis-runtime';
export declare class Query<T extends any> {
    readonly condition: Condition<T>;
    readonly orderBy: Array<SortPart<T>>;
    readonly skip: number;
    readonly limit: number;
    constructor(condition?: Condition<T>, orderBy?: Array<SortPart<T>>, skip?: number, limit?: number);
    static properties: string[];
    static propertyTypes(T: ReifiedType): {
        condition: (ReifiedType<unknown> | typeof Condition)[];
        orderBy: (ArrayConstructor | (ReifiedType<unknown> | typeof SortPart)[])[];
        skip: NumberConstructor[];
        limit: NumberConstructor[];
    };
    copy: (values: Partial<Query<T>>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
    static constructorFunction1comPropChaincomQueryTQueryTConditioncomQueryTListcomSortPartcomQueryTIntInt<T extends any>(makeCondition: ((a: PropChain<T, T>) => Condition<T>), orderBy: Array<SortPart<T>>, skip?: number, limit?: number): Query<T>;
}
