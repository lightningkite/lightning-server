import { ReifiedType } from '@lightningkite/khrysalis-runtime';
export declare class EntryChange<T extends any> {
    readonly old: (T | null);
    readonly _new: (T | null);
    constructor(old?: (T | null), _new?: (T | null));
    static properties: string[];
    static propertiesJsonOverride: {
        _new: string;
    };
    static propertyTypes(T: ReifiedType): {
        old: ReifiedType<unknown>;
        _new: ReifiedType<unknown>;
    };
    copy: (values: Partial<EntryChange<T>>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
}
