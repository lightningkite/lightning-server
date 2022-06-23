import {Condition} from "./Condition";

export type Modification<T> =
    { Chain: Array<Modification<T>> }
    | { IfNotNull: Modification<T> }
    | { Assign: T }
    | { CoerceAtMost: T }
    | { CoerceAtLeast: T }
    | { Increment: T }
    | { Multiply: T }
    | { AppendString: T }
    | { AppendList: T }
    | { AppendSet: T }
    | { Remove: Condition<any> }
    | { RemoveInstances: T }
    | { DropFirst: boolean }
    | { DropLast: boolean }
    | { PerElement: {
        condition: Condition<any>
        modification: Modification<any>
    } }
    | { Combine: T }
    | { ModifyByKey: Record<string, Modification<any>> }
    | { RemoveKeys: Array<string> }
    | { [P in keyof T]?: Modification<T[P]> }

export function evaluateModification<T>(modification: Modification<T>, model: T): T {
    const key = Object.keys(modification)[0]
    const value = (modification as any)[key]
    switch(key) {
        case "Assign":
            return value
        case "Chain":
            let current = model
            for(const item of value as Array<Modification<T>>)
                current = evaluateModification(item, current)
            return current
        case "IfNotNull":
            if(model !== null && model !== undefined) {
                return value
            }
            return model
        case "CoerceAtMost":
            throw new Error("CoerceAtMost is not supported yet")
        case "CoerceAtLeast":
            throw new Error("CoerceAtLeast is not supported yet")
        case "Increment":
            throw new Error("Increment is not supported yet")
        case "Multiply":
            throw new Error("Multiply is not supported yet")
        case "AppendString":
            throw new Error("AppendString is not supported yet")
        case "AppendList":
            throw new Error("AppendList is not supported yet")
        case "AppendSet":
            throw new Error("AppendSet is not supported yet")
        case "Remove":
            throw new Error("Remove is not supported yet")
        case "RemoveInstances":
            throw new Error("RemoveInstances is not supported yet")
        case "DropFirst":
            throw new Error("DropFirst is not supported yet")
        case "DropLast":
            throw new Error("DropLast is not supported yet")
        case "PerElement":
            throw new Error("PerElement is not supported yet")
        case "Combine":
            throw new Error("Combine is not supported yet")
        case "ModifyByKey":
            throw new Error("ModifyByKey is not supported yet")
        case "RemoveKeys":
            throw new Error("RemoveKeys is not supported yet")
        default:
            const copy: any = {...model}
            copy[key] = evaluateModification(value as Modification<any>, (model as any)[key])
            return copy
    }
}
