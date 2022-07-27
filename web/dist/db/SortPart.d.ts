import { Comparator, ReifiedType, TProperty1 } from '@lightningkite/khrysalis-runtime';
export declare class SortPart<T extends any> {
    readonly field: (keyof T & string);
    readonly ascending: boolean;
    constructor(field: (keyof T & string), ascending?: boolean);
    static properties: string[];
    static propertyTypes(T: ReifiedType): {
        field: (StringConstructor | ReifiedType<unknown>)[];
        ascending: BooleanConstructor[];
    };
    copy: (values: Partial<SortPart<T>>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
    static constructorKProperty1comSortPartTAnyBoolean<T extends any>(field: TProperty1<T, any>, ascending?: boolean): SortPart<T>;
}
export declare function xListComparatorGet<T extends any>(this_: Array<SortPart<T>>): (Comparator<T> | null);
