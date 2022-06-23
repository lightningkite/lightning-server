export type Condition<T> =
    { Never: true }
    | { Always: true }
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
    | ArrayCondition<T, any>
    | { SizesEquals: number }
    | { Exists: boolean }
    | { IfNotNull: Condition<T> }
    | { [P in keyof T]?: Condition<T[P]> }

type ArrayCondition<T, E> =
    T extends Array<E> ? ({ AllElements: Condition<E> }
            | { AnyElements: Condition<E> })
        : never

interface Thing {
    _id: string
}

export function evaluateCondition<T>(condition: Condition<T>, model: T): boolean {
    const key = Object.keys(condition)[0]
    const value = (condition as any)[key]
    switch (key) {
        case "Never":
            return false
        case "Always":
            return true
        case "And":
            return (value as Array<Condition<T>>).every(x => evaluateCondition(x, model))
        case "Or":
            return (value as Array<Condition<T>>).some(x => evaluateCondition(x, model))
        case "Not":
            return !evaluateCondition(value as Condition<T>, model)
        case "Equal":
            return model === value
        case "NotEqual":
            return model !== value
        case "Inside":
            return (value as Array<T>).indexOf(model) !== -1
        case "NotInside":
            return (value as Array<T>).indexOf(model) === -1
        case "GreaterThan":
            return model > value
        case "LessThan":
            return model < value
        case "GreaterThanOrEqual":
            return model >= value
        case "LessThanOrEqual":
            return model <= value
        case "Search":
            const v = value as {
                value: string;
                ignoreCase: boolean;
            }
            if (v.ignoreCase)
                return ((model as unknown as string).toLowerCase().indexOf(v.value) !== -1)
            else
                return ((model as unknown as string).indexOf(v.value) !== -1)
        case "IntBitsClear":
            return ((model as unknown as number) & value) === 0
        case "IntBitsSet":
            return ((model as unknown as number) & value) === value
        case "IntBitsAnyClear":
            return ((model as unknown as number) & value) < value
        case "IntBitsAnySet":
            return ((model as unknown as number) & value) > 0
        case "AllElements":
            return (model as unknown as Array<any>).every(x => evaluateCondition(value as Condition<any>, x))
        case "AnyElements":
            return (model as unknown as Array<any>).some(x => evaluateCondition(value as Condition<any>, x))
        case "SizesEquals":
            return (model as unknown as Array<any>).length === value
        case "Exists":
            return true
        case "IfNotNull":
            return model !== null && model !== undefined
        default:
            return evaluateCondition(value as Condition<any>, (model as any)[key])
    }
}
