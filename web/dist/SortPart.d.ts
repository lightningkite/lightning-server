import { PartialDataClassProperty } from './DataClassProperty';
import { Comparator, ReifiedType } from '@lightningkite/khrysalis-runtime';
export declare class SortPart<T extends any> {
    readonly field: PartialDataClassProperty<T>;
    readonly ascending: boolean;
    constructor(field: PartialDataClassProperty<T>, ascending?: boolean);
    static properties: string[];
    static propertyTypes(T: ReifiedType): {
        field: (ReifiedType<unknown> | typeof PartialDataClassProperty)[];
        ascending: BooleanConstructor[];
    };
    copy: (values: Partial<SortPart<T>>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
}
export declare function xListComparatorGet<T extends any>(this_: Array<SortPart<T>>): (Comparator<T> | null);
