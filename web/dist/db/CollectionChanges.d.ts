import { EntryChange } from './EntryChange';
import { HasId } from './HasId';
import { Comparable, ReifiedType } from '@lightningkite/khrysalis-runtime';
export declare class CollectionChanges<T extends any> {
    readonly changes: Array<EntryChange<T>>;
    constructor(changes?: Array<EntryChange<T>>);
    static properties: string[];
    static propertyTypes(T: ReifiedType): {
        changes: (ArrayConstructor | (ReifiedType<unknown> | typeof EntryChange)[])[];
    };
    copy: (values: Partial<CollectionChanges<T>>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
    static pair<T extends any>(old?: (T | null), _new?: (T | null)): CollectionChanges<T>;
}
export declare function xListApply<T extends HasId<ID>, ID extends Comparable<ID>>(this_: Array<T>, changes: CollectionChanges<T>): Array<T>;
export declare function xCollectionChangesMap<T extends any, B extends any>(this_: CollectionChanges<T>, mapper: ((a: T) => B)): CollectionChanges<B>;
