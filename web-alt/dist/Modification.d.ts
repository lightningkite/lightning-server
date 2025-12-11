import { Condition } from "./Condition";
export declare type Modification<T> = {
    Assign: T;
} | {
    Chain: Array<Modification<T>>;
} | {
    IfNotNull: Modification<NonNullable<T>>;
} | ArrayModification<T> | ComparableModification<T> | StringModification<T> | NumberModification<T> | {
    [P in keyof T]?: Modification<T[P]>;
};
declare type ArrayModification<T> = NoNullGuard<T, T extends Array<infer E> ? {
    ListRemove: Condition<E>;
} | {
    ListAppend: Array<E>;
} | {
    ListDropFirst: true;
} | {
    ListDropLast: true;
} | {
    ListRemoveInstances: Array<E>;
} | {
    SetAppend: Array<E>;
} | {
    SetRemove: Condition<E>;
} | {
    SetDropFirst: true;
} | {
    SetDropLast: true;
} | {
    SetRemoveInstances: Array<E>;
} | {
    ListPerElement: {
        condition: Condition<E>;
        modification: Modification<E>;
    };
} | {
    SetPerElement: {
        condition: Condition<E>;
        modification: Modification<E>;
    };
} : never>;
declare type ComparableModification<T> = NoNullGuard<T, T extends string | number ? {
    CoerceAtMost: T;
} | {
    CoerceAtLeast: T;
} : never>;
declare type StringModification<T> = NoNullGuard<T, T extends string ? {
    AppendString: T;
} : never>;
declare type NumberModification<T> = NoNullGuard<T, T extends number ? {
    Increment: T;
} | {
    Multiply: T;
} : never>;
declare type NoNullGuard<T, V> = [T] extends [Exclude<T, null | undefined>] ? V : never;
export declare function evaluateModification<T>(modification: Modification<T>, model: T): T;
export {};
