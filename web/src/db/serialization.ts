import {Condition} from './Condition'
import {Modification} from './Modification'
import {compareBy, DataClass, parseObject, ReifiedType, TProperty1} from "@lightningkite/khrysalis-runtime";
import {SortPart} from "./SortPart";
import {DataClassPath, DataClassPathAccess, DataClassPathNotNull, DataClassPathSelf} from "./DataClassPath";

(Condition as any).fromJSON = <T>(data: Record<string, any>, types: Array<ReifiedType>): Condition<T> => {
    const type = types[0]
    let key = Object.keys(data)[0]
    switch (key) {
        case "Never":
            return new Condition.Never<T>()
        case "Always":
            return new Condition.Always<T>()
        case "And":
            return new Condition.And(parseObject(data.And, [Array, [Condition, type]]))
        case "Or":
            return new Condition.Or(parseObject(data.Or, [Array, [Condition, type]]))
        case "Not":
            return new Condition.Not(parseObject(data.Not, [Condition, type]))
        case "Equal":
            return new Condition.Equal(parseObject(data.Equal, type))
        case "NotEqual":
            return new Condition.NotEqual(parseObject(data.NotEqual, type))
        case "Inside":
            return new Condition.Inside(parseObject(data.Inside, [Array, type]))
        case "NotInside":
            return new Condition.NotInside(parseObject(data.NotInside, [Array, type]))
        case "GreaterThan":
            return new Condition.GreaterThan(parseObject(data.GreaterThan, type))
        case "LessThan":
            return new Condition.LessThan(parseObject(data.LessThan, type))
        case "GreaterThanOrEqual":
            return new Condition.GreaterThanOrEqual(parseObject(data.GreaterThanOrEqual, type))
        case "LessThanOrEqual":
            return new Condition.LessThanOrEqual(parseObject(data.LessThanOrEqual, type))
        case "StringContains":
            return parseObject(data.StringContains, [Condition.StringContains])
        case "FullTextSearch":
            return parseObject(data.FullTextSearch, [Condition.FullTextSearch])
        case "IntBitsClear":
            return new Condition.IntBitsClear(data.IntBitsClear as number) as unknown as Condition<T>
        case "IntBitsSet":
            return new Condition.IntBitsSet(data.IntBitsSet as number) as unknown as Condition<T>
        case "IntBitsAnyClear":
            return new Condition.IntBitsAnyClear(data.IntBitsAnyClear as number) as unknown as Condition<T>
        case "IntBitsAnySet":
            return new Condition.IntBitsAnySet(data.IntBitsAnySet as number) as unknown as Condition<T>
        case "ListAllElements":
            return new Condition.ListAllElements(parseObject(data.ListAllElements, [Condition, type[1]])) as unknown as Condition<T>
        case "ListAnyElements":
            return new Condition.ListAnyElements(parseObject(data.ListAnyElements, [Condition, type[1]])) as unknown as Condition<T>
        case "ListSizesEquals":
            return new Condition.ListSizesEquals(data.ListSizesEquals as number) as unknown as Condition<T>
        case "SetAllElements":
            return new Condition.SetAllElements(parseObject(data.SetAllElements, [Condition, type[1]])) as unknown as Condition<T>
        case "SetAnyElements":
            return new Condition.SetAnyElements(parseObject(data.SetAnyElements, [Condition, type[1]])) as unknown as Condition<T>
        case "SetSizesEquals":
            return new Condition.SetSizesEquals(data.SetSizesEquals as number) as unknown as Condition<T>
        case "Exists":
            return new Condition.Exists(data.Exists as string) as unknown as Condition<T>
        case "OnKey":
            return parseObject(data.OnKey, [Condition.OnKey, type[2]])
        case "IfNotNull":
            return new Condition.IfNotNull(parseObject(data.IfNotNull, [Condition, type]))
        default:
            const baseType = type[0]
            const propTypes = baseType.propertyTypes(type.slice(1))
            const innerType = propTypes[key]
            return new Condition.OnField(key as TProperty1<T, unknown>, parseObject(data[key], [Condition, innerType]))
    }
}

(Condition.Never as any).prototype.toJSON = function (this: Condition.Never<any>): Record<string, any> {
    return {Never: true}
};
(Condition.Always as any).prototype.toJSON = function (this: Condition.Always<any>): Record<string, any> {
    return {Always: true}
};
(Condition.And as any).prototype.toJSON = function (this: Condition.And<any>): Record<string, any> {
    return {And: this.conditions}
};
(Condition.Or as any).prototype.toJSON = function (this: Condition.Or<any>): Record<string, any> {
    return {Or: this.conditions}
};
(Condition.Not as any).prototype.toJSON = function (this: Condition.Not<any>): Record<string, any> {
    return {Not: this.condition}
};
(Condition.Equal as any).prototype.toJSON = function (this: Condition.Equal<any>): Record<string, any> {
    return {Equal: this.value}
};
(Condition.NotEqual as any).prototype.toJSON = function (this: Condition.NotEqual<any>): Record<string, any> {
    return {NotEqual: this.value}
};
(Condition.Inside as any).prototype.toJSON = function (this: Condition.Inside<any>): Record<string, any> {
    return {Inside: this.values}
};
(Condition.NotInside as any).prototype.toJSON = function (this: Condition.NotInside<any>): Record<string, any> {
    return {NotInside: this.values}
};
(Condition.GreaterThan as any).prototype.toJSON = function (this: Condition.GreaterThan<any>): Record<string, any> {
    return {GreaterThan: this.value}
};
(Condition.LessThan as any).prototype.toJSON = function (this: Condition.LessThan<any>): Record<string, any> {
    return {LessThan: this.value}
};
(Condition.GreaterThanOrEqual as any).prototype.toJSON = function (this: Condition.GreaterThanOrEqual<any>): Record<string, any> {
    return {GreaterThanOrEqual: this.value}
};
(Condition.LessThanOrEqual as any).prototype.toJSON = function (this: Condition.LessThanOrEqual<any>): Record<string, any> {
    return {LessThanOrEqual: this.value}
};
(Condition.StringContains as any).prototype.toJSON = function (this: Condition.StringContains): Record<string, any> {
    return {StringContains: {value: this.value, ignoreCase: this.ignoreCase}}
};
(Condition.FullTextSearch as any).prototype.toJSON = function (this: Condition.FullTextSearch<any>): Record<string, any> {
    return {FullTextSearch: {value: this.value, ignoreCase: this.ignoreCase}}
};
(Condition.IntBitsClear as any).prototype.toJSON = function (this: Condition.IntBitsClear): Record<string, any> {
    return {IntBitsClear: this.mask}
};
(Condition.IntBitsSet as any).prototype.toJSON = function (this: Condition.IntBitsSet): Record<string, any> {
    return {IntBitsSet: this.mask}
};
(Condition.IntBitsAnyClear as any).prototype.toJSON = function (this: Condition.IntBitsAnyClear): Record<string, any> {
    return {IntBitsAnyClear: this.mask}
};
(Condition.IntBitsAnySet as any).prototype.toJSON = function (this: Condition.IntBitsAnySet): Record<string, any> {
    return {IntBitsAnySet: this.mask}
};
(Condition.ListAllElements as any).prototype.toJSON = function (this: Condition.ListAllElements<any>): Record<string, any> {
    return {ListAllElements: this.condition}
};
(Condition.ListAnyElements as any).prototype.toJSON = function (this: Condition.ListAnyElements<any>): Record<string, any> {
    return {ListAnyElements: this.condition}
};
(Condition.ListSizesEquals as any).prototype.toJSON = function (this: Condition.ListSizesEquals<any>): Record<string, any> {
    return {ListSizesEquals: this.count}
};
(Condition.SetAllElements as any).prototype.toJSON = function (this: Condition.SetAllElements<any>): Record<string, any> {
    return {SetAllElements: this.condition}
};
(Condition.SetAnyElements as any).prototype.toJSON = function (this: Condition.SetAnyElements<any>): Record<string, any> {
    return {SetAnyElements: this.condition}
};
(Condition.SetSizesEquals as any).prototype.toJSON = function (this: Condition.SetSizesEquals<any>): Record<string, any> {
    return {SetSizesEquals: this.count}
};
(Condition.Exists as any).prototype.toJSON = function (this: Condition.Exists<any>): Record<string, any> {
    return {Exists: this.key}
};
(Condition.OnKey as any).prototype.toJSON = function (this: Condition.OnKey<any>): Record<string, any> {
    return {OnKey: {key: this.key, condition: this.condition}}
};
(Condition.IfNotNull as any).prototype.toJSON = function (this: Condition.IfNotNull<any>): Record<string, any> {
    return {IfNotNull: this.condition}
};
(Condition.OnField as any).prototype.toJSON = function (this: Condition.OnField<any, any>): Record<string, any> {
    const result: Record<string, any> = {}
    result[this.key] = this.condition
    return result
};

(Modification as any).fromJSON = <T>(data: Record<string, any>, types: Array<ReifiedType>): Modification<T> => {
    const type = types[0]
    let key = Object.keys(data)[0]
    switch (key) {
        case "Chain":
            return new Modification.Chain(parseObject(data.Chain, [Array, [Modification, type]]))
        case "IfNotNull":
            return new Modification.IfNotNull(parseObject(data.IfNotNull, [Modification, type])) as unknown as Modification<T>
        case "Assign":
            return new Modification.Assign(parseObject<T>(data.Assign, type))
        case "CoerceAtMost":
            return new Modification.CoerceAtMost(parseObject<T>(data.CoerceAtMost, type))
        case "CoerceAtLeast":
            return new Modification.CoerceAtLeast(parseObject<T>(data.CoerceAtLeast, type))
        case "Increment":
            return new Modification.Increment(data.Increment as number) as unknown as Modification<T>
        case "Multiply":
            return new Modification.Multiply(data.Multiply as number) as unknown as Modification<T>
        case "AppendString":
            return new Modification.AppendString(data.AppendString as string) as unknown as Modification<T>
        case "ListAppend":
            return new Modification.ListAppend(parseObject<Array<any>>(data.ListAppend, [Array, type[1]])) as unknown as Modification<T>
        case "ListRemove":
            return new Modification.ListRemove(parseObject(data.ListRemove, [Condition, type[1]])) as unknown as Modification<T>
        case "ListRemoveInstances":
            return new Modification.ListRemoveInstances(parseObject<Array<any>>(data.ListRemoveInstances, [Array, type[1]])) as unknown as Modification<T>
        case "ListDropFirst":
            return new Modification.ListDropFirst() as unknown as Modification<T>
        case "ListDropLast":
            return new Modification.ListDropLast() as unknown as Modification<T>
        case "SetAppend":
            return new Modification.SetAppend(parseObject<Set<any>>(data.SetAppend, [Array, type[1]])) as unknown as Modification<T>
        case "SetRemove":
            return new Modification.SetRemove(parseObject(data.SetRemove, [Condition, type[1]])) as unknown as Modification<T>
        case "SetRemoveInstances":
            return new Modification.SetRemoveInstances(parseObject<Set<any>>(data.SetRemoveInstances, [Array, type[1]])) as unknown as Modification<T>
        case "SetDropFirst":
            return new Modification.SetDropFirst() as unknown as Modification<T>
        case "SetDropLast":
            return new Modification.SetDropLast() as unknown as Modification<T>
        case "ListPerElement":
            return parseObject(data.PerElement, [Modification.ListPerElement, type[1]])
        case "SetPerElement":
            return parseObject(data.PerElement, [Modification.SetPerElement, type[1]])
        case "Combine":
            return new Modification.Combine(parseObject<Map<string, any>>(data.Combine, [Map, [String], type[2]])) as unknown as Modification<T>
        case "ModifyByKey":
            return new Modification.ModifyByKey(parseObject<Map<string, Modification<any>>>(data.ModifyByKey, [Map, [String], [Modification, type[2]]])) as unknown as Modification<T>
        case "RemoveKeys":
            return new Modification.RemoveKeys(parseObject<Set<string>>(data.RemoveKeys, [Set, [String]])) as unknown as Modification<T>
        default:
            const baseType = type[0]
            const propTypes = baseType.propertyTypes(type.slice(1))
            const innerType = propTypes[key]
            return new Modification.OnField(key as TProperty1<T, unknown>, parseObject(data[key], [Modification, innerType]))
    }
}

(Modification.Chain as any).prototype.toJSON = function (this: Modification.Chain<any>): Record<string, any> {
    return {Chain: this.modifications}
};
(Modification.IfNotNull as any).prototype.toJSON = function (this: Modification.IfNotNull<any>): Record<string, any> {
    return {IfNotNull: this.modification}
};
(Modification.Assign as any).prototype.toJSON = function (this: Modification.Assign<any>): Record<string, any> {
    return {Assign: this.value}
};
(Modification.CoerceAtMost as any).prototype.toJSON = function (this: Modification.CoerceAtMost<any>): Record<string, any> {
    return {CoerceAtMost: this.value}
};
(Modification.CoerceAtLeast as any).prototype.toJSON = function (this: Modification.CoerceAtLeast<any>): Record<string, any> {
    return {CoerceAtLeast: this.value}
};
(Modification.Increment as any).prototype.toJSON = function (this: Modification.Increment<any>): Record<string, any> {
    return {Increment: this.by}
};
(Modification.Multiply as any).prototype.toJSON = function (this: Modification.Multiply<any>): Record<string, any> {
    return {Multiply: this.by}
};
(Modification.AppendString as any).prototype.toJSON = function (this: Modification.AppendString): Record<string, any> {
    return {AppendString: this.value}
};
(Modification.ListAppend as any).prototype.toJSON = function (this: Modification.ListAppend<any>): Record<string, any> {
    return {ListAppend: this.items}
};
(Modification.ListRemove as any).prototype.toJSON = function (this: Modification.ListRemove<any>): Record<string, any> {
    return {ListRemove: this.condition}
};
(Modification.ListRemoveInstances as any).prototype.toJSON = function (this: Modification.ListRemoveInstances<any>): Record<string, any> {
    return {ListRemoveInstances: this.items}
};
(Modification.ListDropFirst as any).prototype.toJSON = function (this: Modification.ListDropFirst<any>): Record<string, any> {
    return {ListDropFirst: true}
};
(Modification.ListDropLast as any).prototype.toJSON = function (this: Modification.ListDropLast<any>): Record<string, any> {
    return {ListDropLast: true}
};
(Modification.SetAppend as any).prototype.toJSON = function (this: Modification.SetAppend<any>): Record<string, any> {
    return {SetAppend: this.items}
};
(Modification.SetRemove as any).prototype.toJSON = function (this: Modification.SetRemove<any>): Record<string, any> {
    return {SetRemove: this.condition}
};
(Modification.SetRemoveInstances as any).prototype.toJSON = function (this: Modification.SetRemoveInstances<any>): Record<string, any> {
    return {SetRemoveInstances: this.items}
};
(Modification.SetDropFirst as any).prototype.toJSON = function (this: Modification.SetDropFirst<any>): Record<string, any> {
    return {SetDropFirst: true}
};
(Modification.SetDropLast as any).prototype.toJSON = function (this: Modification.SetDropLast<any>): Record<string, any> {
    return {SetDropLast: true}
};
(Modification.Combine as any).prototype.toJSON = function (this: Modification.Combine<any>): Record<string, any> {
    return {Combine: this.map}
};
(Modification.ModifyByKey as any).prototype.toJSON = function (this: Modification.ModifyByKey<any>): Record<string, any> {
    return {ModifyByKey: this.map}
};
(Modification.RemoveKeys as any).prototype.toJSON = function (this: Modification.RemoveKeys<any>): Record<string, any> {
    return {RemoveKeys: this.fields}
};
(Modification.OnField as any).prototype.toJSON = function (this: Modification.OnField<any, any>): Record<string, any> {
    const result: Record<string, any> = {}
    result[this.key] = this.modification
    return result
};

function stringToPath(str: String): DataClassPath<any, any> {
    let current = new DataClassPathSelf()
    for(const part of str.split('.')) {
        if(part.endsWith('?')) {
            // @ts-ignore
            current = new DataClassPathNotNull(new DataClassPathAccess(current, part.substring(0, part.length-1)))
        } else {
            // @ts-ignore
            current = new DataClassPathAccess(current, part)
        }
    }
    return current
}
(SortPart as any).prototype.toJSON = function (this: SortPart<any>): string {
    return this.ascending ? this.field.toString() : `-${this.field.toString()}`
};
(SortPart as any).fromJSON = <T>(value: string, types: Array<ReifiedType>): SortPart<T> => {
    const descending = value.startsWith('-')
    const realName = descending ? value.substring(1) : value
    return new SortPart<T>(stringToPath(realName), !descending)
}
(DataClassPath as any).prototype.toJSON = function (this: DataClassPath<any, any>): string {
    return this.toString()
};
(DataClassPath as any).fromJSON = <T>(value: string, types: Array<ReifiedType>): DataClassPath<any, any> => {
    return stringToPath(value)
}
