export declare type Condition<T> = {
    Never: true;
} | {
    Always: true;
} | {
    And: Array<Condition<T>>;
} | {
    Or: Array<Condition<T>>;
} | {
    Not: Condition<T>;
} | {
    Equal: T;
} | {
    NotEqual: T;
} | {
    Inside: Array<T>;
} | {
    NotInside: Array<T>;
} | {
    GreaterThan: T;
} | {
    LessThan: T;
} | {
    GreaterThanOrEqual: T;
} | {
    LessThanOrEqual: T;
} | (T extends string ? {
    StringContains: {
        value: string;
        ignoreCase: boolean;
    };
} : never) | {
    FullTextSearch: {
        value: string;
        ignoreCase: boolean;
    };
} | {
    IntBitsClear: number;
} | {
    IntBitsSet: number;
} | {
    IntBitsAnyClear: number;
} | {
    IntBitsAnySet: number;
} | ArrayCondition<T, any> | {
    SizesEquals: number;
} | {
    Exists: boolean;
} | {
    IfNotNull: Condition<T>;
} | {
    [P in keyof T]?: Condition<T[P]>;
};
declare type ArrayCondition<T, E> = T extends Array<E> ? ({
    AllElements: Condition<E>;
} | {
    AnyElements: Condition<E>;
}) : never;
export declare function evaluateCondition<T>(condition: Condition<T>, model: T): boolean;
declare type PathImpl<T, K extends keyof T> = K extends string ? T[K] extends Record<string, any> ? T[K] extends ArrayLike<any> ? K | `${K}.${PathImpl<T[K], Exclude<keyof T[K], keyof any[]>>}` : K | `${K}.${PathImpl<T[K], keyof T[K]>}` : K : never;
declare type Path<T> = PathImpl<T, keyof T> | (keyof T & string);
export declare type DataClassProperty<T, V> = keyof {
    [P in keyof T as T[P] extends V ? P : never]: P;
} & keyof T & string;
declare type PathValue<T, P extends Path<T>> = P extends `${infer K}.${infer Rest}` ? K extends keyof T ? Rest extends Path<T[K]> ? PathValue<T[K], Rest> : never : never : P extends keyof T ? T[P] : never;
declare type PathWithConditionImpl<T, P extends Path<T>> = `${P}:${keyof ConditionMap<PathValue<T, P>>}`;
declare type PathWithCondition<T> = PathWithConditionImpl<T, Path<T>>;
declare type PathWithConditionValue<T, P extends PathWithCondition<T>> = P extends `${infer K}:${infer ConditionKey}` ? K extends Path<T> ? ConditionKey extends keyof ConditionMap<PathValue<T, K>> ? ConditionMap<PathValue<T, K>>[ConditionKey] : never : never : never;
declare type ConditionMap<T> = {
    Never?: true;
    Always?: true;
    Equal?: T;
    NotEqual?: T;
    Inside?: Array<T>;
    NotInside?: Array<T>;
    GreaterThan?: T;
    LessThan?: T;
    GreaterThanOrEqual?: T;
    LessThanOrEqual?: T;
    StringContains?: {
        value: string;
        ignoreCase: boolean;
    };
    FullTextSearch?: {
        value: string;
        ignoreCase: boolean;
    };
    IntBitsClear?: number;
    IntBitsSet?: number;
    IntBitsAnyClear?: number;
    IntBitsAnySet?: number;
    SizesEquals?: number;
    Exists?: boolean;
};
/**
 * May God have mercy on your soul if you need to read the definition of this type.
 * Here's a more reasonable description by example:
 * Imagine you have type TestType = { a: {b: boolean, c: number}, d: boolean }.
 * You can put things like this in here:
 * { "a.c:Equal": 3, "d:Equal": true }
 */
export declare type ConditionBuilder<T> = {
    [P in PathWithCondition<T>]?: PathWithConditionValue<T, P>;
} & {
    ""?: Condition<T>;
};
export declare function condition<T, P extends PathWithCondition<T>>(key: P, value: PathWithConditionValue<T, P>): Condition<T>;
export declare function and<T>(conditionBuilder: ConditionBuilder<T> | Array<Condition<T>>): Condition<T>;
export declare function or<T>(conditionBuilder: ConditionBuilder<T> | Array<Condition<T>>): Condition<T>;
export {};
