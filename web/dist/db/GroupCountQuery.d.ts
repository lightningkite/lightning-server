import { Aggregate } from './Aggregate';
import { Condition } from './Condition';
import { DataClassPathPartial } from './DataClassPath';
import { ReifiedType } from '@lightningkite/khrysalis-runtime';
export declare class GroupCountQuery<Model extends any> {
    readonly condition: Condition<Model>;
    readonly groupBy: DataClassPathPartial<Model>;
    constructor(condition: Condition<Model>, groupBy: DataClassPathPartial<Model>);
    static properties: string[];
    static propertyTypes(Model: ReifiedType): {
        condition: (ReifiedType<unknown> | typeof Condition)[];
        groupBy: (ReifiedType<unknown> | typeof DataClassPathPartial)[];
    };
    copy: (values: Partial<GroupCountQuery<Model>>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
}
export declare class AggregateQuery<Model extends any> {
    readonly aggregate: Aggregate;
    readonly condition: Condition<Model>;
    readonly property: DataClassPathPartial<Model>;
    constructor(aggregate: Aggregate, condition: Condition<Model>, property: DataClassPathPartial<Model>);
    static properties: string[];
    static propertyTypes(Model: ReifiedType): {
        aggregate: (typeof Aggregate)[];
        condition: (ReifiedType<unknown> | typeof Condition)[];
        property: (ReifiedType<unknown> | typeof DataClassPathPartial)[];
    };
    copy: (values: Partial<AggregateQuery<Model>>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
}
export declare class GroupAggregateQuery<Model extends any> {
    readonly aggregate: Aggregate;
    readonly condition: Condition<Model>;
    readonly groupBy: DataClassPathPartial<Model>;
    readonly property: DataClassPathPartial<Model>;
    constructor(aggregate: Aggregate, condition: Condition<Model>, groupBy: DataClassPathPartial<Model>, property: DataClassPathPartial<Model>);
    static properties: string[];
    static propertyTypes(Model: ReifiedType): {
        aggregate: (typeof Aggregate)[];
        condition: (ReifiedType<unknown> | typeof Condition)[];
        groupBy: (ReifiedType<unknown> | typeof DataClassPathPartial)[];
        property: (ReifiedType<unknown> | typeof DataClassPathPartial)[];
    };
    copy: (values: Partial<GroupAggregateQuery<Model>>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
}
