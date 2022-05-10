import { Comparator } from '@lightningkite/khrysalis-runtime';
export declare abstract class PartialDataClassProperty<K> {
    protected constructor();
    abstract readonly name: string;
    abstract anyGet(k: K): (any | null);
    abstract anySet(k: K, v: (any | null)): K;
    abstract readonly compare: (Comparator<K> | null);
    hashCode(): number;
    equals(other: (any | null)): boolean;
}
export declare class DataClassProperty<K, V> extends PartialDataClassProperty<K> {
    readonly name: string;
    readonly get: ((a: K) => V);
    readonly set: ((a: K, b: V) => K);
    readonly compare: (Comparator<K> | null);
    constructor(name: string, get: ((a: K) => V), set: ((a: K, b: V) => K), compare?: (Comparator<K> | null));
    anyGet(k: K): (any | null);
    anySet(k: K, v: (any | null)): K;
}
