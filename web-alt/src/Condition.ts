export type Condition<T> =
    { Never: boolean }
    | { Always: boolean }
    | { And: Array<Condition<T>> }
    | { Or: Array<Condition<T>> }
    | { Not: Condition<T> }
    | { Equal: T }
    | { NotEqual: T }
    | { Inside: Array<T> }
    | { NotInside: Array<T> }
    | { GreaterThan: T }
    | { LessThan: T }
    | { GreaterThanOrEqual: T }
    | { LessThanOrEqual: T }
    | {
        Search: {
            value: string;
            ignoreCase: boolean;
        }
    }
    | { IntBitsClear: number }
    | { IntBitsSet: number }
    | { IntBitsAnyClear: number }
    | { IntBitsAnySet: number }
    | { AllElements: Condition<any> }
    | { AnyElements: Condition<any> }
    | { SizesEquals: number }
    | { Exists: boolean }
    | { IfNotNull: Condition<T> }
    | { [P in keyof T]?: Condition<T[P]> }

interface Thing {
    _id: string
}
