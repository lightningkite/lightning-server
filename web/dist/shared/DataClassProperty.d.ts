export declare type PartialDataClassProperty<T> = keyof T & string;
export declare type DataClassProperty<T, V> = keyof {
    [P in keyof T as T[P] extends V ? P : never]: P;
} & keyof T & string;
export declare function keyGet<K, V>(on: K, key: DataClassProperty<K, V>): V;
export declare function keySet<K, V>(on: K, key: DataClassProperty<K, V>, value: V): K;
