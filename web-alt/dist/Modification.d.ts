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
    AppendList: T;
} | {
    AppendSet: T;
} | {
    Remove: Condition<any>;
} | {
    RemoveInstances: T;
} | {
    DropFirst: boolean;
} | {
    DropLast: boolean;
} | {
    PerElement: {
        condition: Condition<any>;
        modification: Modification<any>;
    };
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
