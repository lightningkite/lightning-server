import { Condition } from './Condition';
import { Modification } from './Modification';
import { ReifiedType, TProperty1 } from '@lightningkite/khrysalis-runtime';
export declare abstract class PropertyPathPartial<K> {
    protected constructor();
    abstract getAny(key: K): (any | null);
    abstract setAny(key: K, any: (any | null)): K;
    abstract readonly properties: Array<TProperty1<any, any>>;
}
export declare abstract class PropertyPath<K, V> extends PropertyPathPartial<K> {
    protected constructor();
    abstract get(key: K): V;
    abstract set(key: K, value: V): K;
    getAny(key: K): V;
    setAny(key: K, any: (any | null)): K;
    abstract mapCondition(condition: Condition<V>): Condition<K>;
    abstract mapModification(modification: Modification<V>): Modification<K>;
}
export declare class PropertyPathSelf<K> extends PropertyPath<K, K> {
    constructor();
    get(key: K): K;
    set(key: K, value: K): K;
    toString(): string;
    hashCode(): number;
    equals(other: (any | null)): boolean;
    get properties(): Array<TProperty1<any, any>>;
    mapCondition(condition: Condition<K>): Condition<K>;
    mapModification(modification: Modification<K>): Modification<K>;
}
export declare class PropertyPathAccess<K, M, V> extends PropertyPath<K, V> {
    readonly first: PropertyPath<K, M>;
    readonly second: TProperty1<M, V>;
    constructor(first: PropertyPath<K, M>, second: TProperty1<M, V>);
    static properties: string[];
    static propertyTypes(K: ReifiedType, M: ReifiedType, V: ReifiedType): {
        first: (ReifiedType<unknown> | typeof PropertyPath)[];
        second: (StringConstructor | ReifiedType<unknown>)[];
    };
    copy: (values: Partial<PropertyPathAccess<K, M, V>>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
    get(key: K): V;
    set(key: K, value: V): K;
    toString(): string;
    get properties(): Array<TProperty1<any, any>>;
    mapCondition(condition: Condition<V>): Condition<K>;
    mapModification(modification: Modification<V>): Modification<K>;
}
export declare class PropertyPathSafeAccess<K, M extends any, V> extends PropertyPath<K, (V | null)> {
    readonly first: PropertyPath<K, (M | null)>;
    readonly second: TProperty1<M, V>;
    constructor(first: PropertyPath<K, (M | null)>, second: TProperty1<M, V>);
    static properties: string[];
    static propertyTypes(K: ReifiedType, M: ReifiedType, V: ReifiedType): {
        first: (ReifiedType<unknown> | typeof PropertyPath)[];
        second: (StringConstructor | ReifiedType<unknown>)[];
    };
    copy: (values: Partial<PropertyPathSafeAccess<K, M, V>>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
    get(key: K): (V | null);
    set(key: K, value: (V | null)): K;
    toString(): string;
    get properties(): Array<TProperty1<any, any>>;
    mapCondition(condition: Condition<(V | null)>): Condition<K>;
    mapModification(modification: Modification<(V | null)>): Modification<K>;
}
export declare function xPropertyPathGet<K, V, V2>(this_: PropertyPath<K, V>, prop: TProperty1<V, V2>): PropertyPathAccess<K, V, V2>;
export declare function xPropertyPathSafeGet<K, V extends any, V2>(this_: PropertyPath<K, (V | null)>, prop: TProperty1<V, V2>): PropertyPathSafeAccess<K, V, V2>;
export declare function path<T extends any>(): PropertyPath<T, T>;
export declare function condition<T extends any>(setup: ((a: PropertyPath<T, T>) => Condition<T>)): Condition<T>;
