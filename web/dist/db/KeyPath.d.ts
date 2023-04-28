export declare type PropertyPathImpl2<T, K extends keyof T, V> = K extends string ? T[K] extends Record<string, any> ? T[K] extends ArrayLike<any> ? K | `${K}.${PropertyPathImpl2<T[K], Exclude<keyof T[K], keyof any[]>, V>}` : K | `${K}.${PropertyPathImpl2<T[K], keyof T[K], V>}` : (T[K] extends V ? K : never) : never;
export declare type DataClassProperty<T, V> = keyof {
    [P in keyof T as T[P] extends V ? P : never]: P;
} & keyof T & string;
export declare type PropertyPath<T, V> = PropertyPathImpl2<T, keyof T, V> | DataClassProperty<T, V>;
export declare type PropertyPathPartial<T> = PropertyPath<T, any>;
