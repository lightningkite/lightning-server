import { EntryChange } from './EntryChange';
import { ReifiedType } from '@lightningkite/khrysalis-runtime';
export declare class ListChange<T extends any> {
    readonly wholeList: (Array<T> | null);
    readonly old: (T | null);
    readonly _new: (T | null);
    constructor(wholeList?: (Array<T> | null), old?: (T | null), _new?: (T | null));
    static properties: string[];
    static propertyTypes(T: ReifiedType): {
        wholeList: (ArrayConstructor | ReifiedType<unknown>)[];
        old: ReifiedType<unknown>;
        _new: ReifiedType<unknown>;
    };
    copy: (values: Partial<ListChange<T>>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
}
export declare function xEntryChangeListChange<T extends any>(this_: EntryChange<T>): ListChange<T>;
