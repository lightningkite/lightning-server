import { Condition } from './Condition';
import { Modification } from './Modification';
import { ReifiedType, TProperty1 } from '@lightningkite/khrysalis-runtime';
export declare abstract class DataClassPathPartial<K> {
    protected constructor();
    abstract getAny(key: K): (any | null);
    abstract setAny(key: K, any: (any | null)): K;
    abstract readonly properties: Array<TProperty1<any, any>>;
}
export declare abstract class DataClassPath<K, V> extends DataClassPathPartial<K> {
    protected constructor();
    abstract get(key: K): (V | null);
    abstract set(key: K, value: V): K;
    getAny(key: K): (V | null);
    setAny(key: K, any: (any | null)): K;
    abstract mapCondition(condition: Condition<V>): Condition<K>;
    abstract mapModification(modification: Modification<V>): Modification<K>;
    prop<P extends keyof V, V2 extends V[P]>(prop: P): DataClassPathAccess<K, V, V2>;
    notNull(): DataClassPathNotNull<K, NonNullable<V>>;
}
export declare class DataClassPathSelf<K> extends DataClassPath<K, K> {
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
export declare class DataClassPathAccess<K, M, V> extends DataClassPath<K, V> {
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
export declare class DataClassPathNotNull<K, V> extends DataClassPath<K, V> {
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
export declare function xDataClassPathNotNullGet<K, V>(this_: DataClassPath<K, (V | null)>): DataClassPathNotNull<K, V>;
