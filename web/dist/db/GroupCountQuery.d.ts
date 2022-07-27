import { Aggregate } from './Aggregate';
import { Condition } from './Condition';
import { ReifiedType } from '@lightningkite/khrysalis-runtime';
export declare class GroupCountQuery<Model extends any> {
    readonly condition: Condition<Model>;
    readonly groupBy: (keyof Model & string);
    constructor(condition: Condition<Model>, groupBy: (keyof Model & string));
    static properties: string[];
    static propertyTypes(Model: ReifiedType): {
        condition: (ReifiedType<unknown> | typeof Condition)[];
        groupBy: (StringConstructor | ReifiedType<unknown>)[];
    };
    copy: (values: Partial<GroupCountQuery<Model>>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
}
export declare class AggregateQuery<Model extends any> {
    readonly aggregate: Aggregate;
    readonly condition: Condition<Model>;
    readonly property: (keyof Model & string);
    constructor(aggregate: Aggregate, condition: Condition<Model>, property: (keyof Model & string));
    static properties: string[];
    static propertyTypes(Model: ReifiedType): {
        aggregate: (typeof Aggregate)[];
        condition: (ReifiedType<unknown> | typeof Condition)[];
        property: (StringConstructor | ReifiedType<unknown>)[];
    };
    copy: (values: Partial<AggregateQuery<Model>>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
}
export declare class GroupAggregateQuery<Model extends any> {
    readonly aggregate: Aggregate;
    readonly condition: Condition<Model>;
    readonly groupBy: (keyof Model & string);
    readonly property: (keyof Model & string);
    constructor(aggregate: Aggregate, condition: Condition<Model>, groupBy: (keyof Model & string), property: (keyof Model & string));
    static properties: string[];
    static propertyTypes(Model: ReifiedType): {
        aggregate: (typeof Aggregate)[];
        condition: (ReifiedType<unknown> | typeof Condition)[];
        groupBy: (StringConstructor | ReifiedType<unknown>)[];
        property: (StringConstructor | ReifiedType<unknown>)[];
    };
    copy: (values: Partial<GroupAggregateQuery<Model>>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
}
