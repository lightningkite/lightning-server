import { Condition } from "./Condition";
export declare type Modification<T> = {
    Chain: Array<Modification<T>>;
} | {
    IfNotNull: Modification<T>;
} | {
    Assign: T;
} | {
    CoerceAtMost: T;
} | {
    CoerceAtLeast: T;
} | {
    Increment: T;
} | {
    Multiply: T;
} | {
    AppendString: T;
} | {
    ListAppend: T;
} | {
    ListRemove: Condition<any>;
} | {
    ListRemoveInstances: T;
} | {
    ListDropFirst: boolean;
} | {
    ListDropLast: boolean;
} | {
    ListPerElement: {
        condition: Condition<any>;
        modification: Modification<any>;
    };
} | {
    SetRemove: Condition<any>;
} | {
    SetRemoveInstances: T;
} | {
    SetDropFirst: boolean;
} | {
    SetDropLast: boolean;
} | {
    SetPerElement: {
        condition: Condition<any>;
        modification: Modification<any>;
    };
} | {
    SetAppend: T;
} | {
    Combine: T;
} | {
    ModifyByKey: Record<string, Modification<any>>;
} | {
    RemoveKeys: Array<string>;
} | {
    [P in keyof T]?: Modification<T[P]>;
};
export declare function evaluateModification<T>(modification: Modification<T>, model: T): T;
