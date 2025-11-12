import { Condition } from "./Condition";
export interface SerializableProperty<Owner, Value> {
    name: string;
}
export declare function simplify<T>(condition: Condition<T>): Condition<T>;
export declare function finalSimplify<T>(cond: Condition<T>): Condition<T>;
export declare function reduceAnd<T>(a: Condition<T>, b: Condition<T>): Condition<T>;
export declare function reduceOr<T>(a: Condition<T>, b: Condition<T>): Condition<T>;
