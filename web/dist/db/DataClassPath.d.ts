import { Condition } from './Condition';
import { Modification } from './Modification';
import { ReifiedType, TProperty1 } from '@lightningkite/khrysalis-runtime';
export declare abstract class DataClassPathPartial<K extends any> {
    protected constructor();
    abstract getAny(key: K): (any | null);
    abstract setAny(key: K, any: (any | null)): K;
    abstract readonly properties: Array<TProperty1<any, any>>;
    abstract hashCode(): number;
    abstract toString(): string;
    abstract equals(other: (any | null)): boolean;
}
export declare abstract class DataClassPath<K extends any, V extends any> extends DataClassPathPartial<K> {
    protected constructor();
    abstract get(key: K): (V | null);
    abstract set(key: K, value: V): K;
    getAny(key: K): (any | null);
    setAny(key: K, any: (any | null)): K;
    abstract mapCondition(condition: Condition<V>): Condition<K>;
    abstract mapModification(modification: Modification<V>): Modification<K>;
    prop<V2>(prop: TProperty1<V, V2>): DataClassPathAccess<K, V, V2>;
}
export declare class DataClassPathSelf<K extends any> extends DataClassPath<K, K> {
    constructor();
    get(key: K): (K | null);
    set(key: K, value: K): K;
    toString(): string;
    hashCode(): number;
    equals(other: (any | null)): boolean;
    get properties(): Array<TProperty1<any, any>>;
    mapCondition(condition: Condition<K>): Condition<K>;
    mapModification(modification: Modification<K>): Modification<K>;
}
export declare class DataClassPathAccess<K extends any, M extends any, V extends any> extends DataClassPath<K, V> {
    readonly first: DataClassPath<K, M>;
    readonly second: TProperty1<M, V>;
    constructor(first: DataClassPath<K, M>, second: TProperty1<M, V>);
    static properties: string[];
    static propertyTypes(K: ReifiedType, M: ReifiedType, V: ReifiedType): {
        first: (ReifiedType<unknown> | typeof DataClassPath)[];
        second: (StringConstructor | ReifiedType<unknown>)[];
    };
    copy: (values: Partial<DataClassPathAccess<K, M, V>>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
    get(key: K): (V | null);
    set(key: K, value: V): K;
    toString(): string;
    get properties(): Array<TProperty1<any, any>>;
    mapCondition(condition: Condition<V>): Condition<K>;
    mapModification(modification: Modification<V>): Modification<K>;
}
export declare class DataClassPathNotNull<K extends any, V extends any> extends DataClassPath<K, V> {
    readonly wraps: DataClassPath<K, (V | null)>;
    constructor(wraps: DataClassPath<K, (V | null)>);
    static properties: string[];
    static propertyTypes(K: ReifiedType, V: ReifiedType): {
        wraps: (ReifiedType<unknown> | typeof DataClassPath)[];
    };
    copy: (values: Partial<DataClassPathNotNull<K, V>>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
    get properties(): Array<TProperty1<any, any>>;
    get(key: K): (V | null);
    set(key: K, value: V): K;
    toString(): string;
    mapCondition(condition: Condition<V>): Condition<K>;
    mapModification(modification: Modification<V>): Modification<K>;
}
export declare class DataClassPathList<K extends any, V extends any> extends DataClassPath<K, V> {
    readonly wraps: DataClassPath<K, Array<V>>;
    constructor(wraps: DataClassPath<K, Array<V>>);
    static properties: string[];
    static propertyTypes(K: ReifiedType, V: ReifiedType): {
        wraps: (ReifiedType<unknown> | typeof DataClassPath)[];
    };
    copy: (values: Partial<DataClassPathList<K, V>>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
    get properties(): Array<TProperty1<any, any>>;
    get(key: K): (V | null);
    set(key: K, value: V): K;
    toString(): string;
    mapCondition(condition: Condition<V>): Condition<K>;
    mapModification(modification: Modification<V>): Modification<K>;
}
export declare class DataClassPathSet<K extends any, V extends any> extends DataClassPath<K, V> {
    readonly wraps: DataClassPath<K, Set<V>>;
    constructor(wraps: DataClassPath<K, Set<V>>);
    static properties: string[];
    static propertyTypes(K: ReifiedType, V: ReifiedType): {
        wraps: (ReifiedType<unknown> | typeof DataClassPath)[];
    };
    copy: (values: Partial<DataClassPathSet<K, V>>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
    get properties(): Array<TProperty1<any, any>>;
    get(key: K): (V | null);
    set(key: K, value: V): K;
    toString(): string;
    mapCondition(condition: Condition<V>): Condition<K>;
    mapModification(modification: Modification<V>): Modification<K>;
}
export declare function notNullGet<K extends any, V extends any>(this_: DataClassPath<K, (V | null)>): DataClassPathNotNull<K, V>;
export declare function listElementsGet<K extends any, V extends any>(this_: DataClassPath<K, Array<V>>): DataClassPathList<K, V>;
export declare function setElementsGet<K extends any, V extends any>(this_: DataClassPath<K, Set<V>>): DataClassPathSet<K, V>;
