import { DataClassPathPartial } from './DataClassPath';
import { Comparator, ReifiedType, TProperty1 } from '@lightningkite/khrysalis-runtime';
export declare class SortPart<T extends any> {
    readonly field: DataClassPathPartial<T>;
    readonly ascending: boolean;
    readonly ignoreCase: boolean;
    constructor(field: DataClassPathPartial<T>, ascending?: boolean, ignoreCase?: boolean);
    static properties: string[];
    static propertyTypes(T: ReifiedType): {
        field: (ReifiedType<unknown> | typeof DataClassPathPartial)[];
        ascending: BooleanConstructor[];
        ignoreCase: BooleanConstructor[];
    };
    copy: (values: Partial<SortPart<T>>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
    static constructorKProperty1comSortPartTAnyBooleanBoolean<T extends any>(field: TProperty1<T, any>, ascending?: boolean, ignoreCase?: boolean): SortPart<T>;
}
export declare function xListComparatorGet<T extends any>(this_: Array<SortPart<T>>): (Comparator<T> | null);
