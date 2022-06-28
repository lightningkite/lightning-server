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
    | (T extends string ? {
    StringContains: {
        value: string;
        ignoreCase: boolean;
    }
} : never)
    | {
    FullTextSearch: {
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
        case "StringContains":
            const v = value as {
                value: string;
                ignoreCase: boolean;
            }
            if (v.ignoreCase)
                return ((model as unknown as string).toLowerCase().indexOf(v.value) !== -1)
            else
                return ((model as unknown as string).indexOf(v.value) !== -1)
        case "FullTextSearch":
            const v2 = value as {
                value: string;
                ignoreCase: boolean;
            }
            if (v2.ignoreCase)
                return ((model as unknown as string).toLowerCase().indexOf(v2.value) !== -1)
            else
                return ((model as unknown as string).indexOf(v2.value) !== -1)
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

type PathImpl<T, K extends keyof T> =
    K extends string
        ? T[K] extends Record<string, any>
            ? T[K] extends ArrayLike<any>
                ? K | `${K}.${PathImpl<T[K], Exclude<keyof T[K], keyof any[]>>}`
                : K | `${K}.${PathImpl<T[K], keyof T[K]>}`
            : K
        : never;

type PathImpl2<T, K extends keyof T, V> =
    K extends string
        ? T[K] extends Record<string, any>
            ? T[K] extends ArrayLike<any>
                ? K | `${K}.${PathImpl2<T[K], Exclude<keyof T[K], keyof any[]>, V>}`
                : K | `${K}.${PathImpl2<T[K], keyof T[K], V>}`
            : (T[K] extends V ? K : never)
        : never;
type Path<T> = PathImpl<T, keyof T> | (keyof T & string);
export type DataClassProperty<T, V> = keyof { [ P in keyof T as T[P] extends V ? P : never ] : P } & keyof T & string;
type Path2<T, V> = PathImpl2<T, keyof T, V> | DataClassProperty<T, V>;

type PathValue<T, P extends Path<T>> =
    P extends `${infer K}.${infer Rest}`
        ? K extends keyof T
            ? Rest extends Path<T[K]>
                ? PathValue<T[K], Rest>
                : never
            : never
        : P extends keyof T
            ? T[P]
            : never;

type PathWithConditionImpl<T, P extends Path<T>> = `${P}:${keyof ConditionMap<PathValue<T, P>>}`
type PathWithCondition<T> = PathWithConditionImpl<T, Path<T>>

type PathWithConditionValue<T, P extends PathWithCondition<T>> =
    P extends `${infer K}:${infer ConditionKey}`
        ? K extends Path<T>
            ? ConditionKey extends keyof ConditionMap<PathValue<T, K>>
                ? ConditionMap<PathValue<T, K>>[ConditionKey]
                : never
            : never
        : never

type ConditionMap<T> = {
    Never?: true
    Always?: true
    Equal?: T
    NotEqual?: T
    Inside?: Array<T>
    NotInside?: Array<T>
    GreaterThan?: T
    LessThan?: T
    GreaterThanOrEqual?: T
    LessThanOrEqual?: T
    StringContains?: {
        value: string;
        ignoreCase: boolean;
    }
    FullTextSearch?: {
        value: string;
        ignoreCase: boolean;
    }
    IntBitsClear?: number
    IntBitsSet?: number
    IntBitsAnyClear?: number
    IntBitsAnySet?: number
    SizesEquals?: number
    Exists?: boolean
}

/**
 * May God have mercy on your soul if you need to read the definition of this type.
 * Here's a more reasonable description by example:
 * Imagine you have type TestType = { a: {b: boolean, c: number}, d: boolean }.
 * You can put things like this in here:
 * { "a.c:Equal": 3, "d:Equal": true }
 */
export type ConditionBuilder<T> = {
    [P in PathWithCondition<T>]?: PathWithConditionValue<T, P>
} & { ""?: Condition<T> }

export function condition<T, P extends PathWithCondition<T>>(key: P, value: PathWithConditionValue<T, P>): Condition<T> {
    const parts = key.split(':')
    const path = parts[0]
    const comparison = parts[1]
    const pathParts = path.split('.')
    let current: Record<string, any> = { }
    current[comparison] = value
    for(const part of pathParts.reverse()) {
        const past = current
        current = {}
        current[part] = past
    }
    return current as Condition<T>
}
export function and<T>(conditionBuilder: ConditionBuilder<T> | Array<Condition<T>>): Condition<T> {
    if(Array.isArray(conditionBuilder)) return { And: conditionBuilder }
    let subconditions: Array<Condition<T>> = []

    for(const key in conditionBuilder) {
        if(key.length === 0) {
            subconditions.push(conditionBuilder[""] as Condition<T>)
            continue
        }
        subconditions.push(condition(key as PathWithCondition<T>, (conditionBuilder as any)[key]))
    }

    if (subconditions.length == 1) return subconditions[0]
    else if(subconditions.length == 0) return { "Always": true }
    else return { "And": subconditions }
}

export function or<T>(conditionBuilder: ConditionBuilder<T> | Array<Condition<T>>): Condition<T> {
    if(Array.isArray(conditionBuilder)) return { Or: conditionBuilder }
    let subconditions: Array<Condition<T>> = []

    for(const key in conditionBuilder) {
        if(key.length === 0) {
            subconditions.push(conditionBuilder[""] as Condition<T>)
            continue
        }
        subconditions.push(condition(key as PathWithCondition<T>, (conditionBuilder as any)[key]))
    }

    if (subconditions.length == 1) return subconditions[0]
    else if(subconditions.length == 0) return { "Always": true }
    else return { "Or": subconditions }
}
