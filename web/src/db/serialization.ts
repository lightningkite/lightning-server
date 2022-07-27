import {Condition} from './Condition'
import {Modification} from './Modification'
import {compareBy, DataClass, parseObject, ReifiedType, TProperty1} from "@lightningkite/khrysalis-runtime";
import {SortPart} from "./SortPart";

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
        case "AllElements":
            return new Condition.AllElements(parseObject(data.AllElements, [Condition, type[1]])) as unknown as Condition<T>
        case "AnyElements":
            return new Condition.AnyElements(parseObject(data.AnyElements, [Condition, type[1]])) as unknown as Condition<T>
        case "SizesEquals":
            return new Condition.SizesEquals(data.SizesEquals as number) as unknown as Condition<T>
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
(Condition.AllElements as any).prototype.toJSON = function (this: Condition.AllElements<any>): Record<string, any> {
    return {AllElements: this.condition}
};
(Condition.AnyElements as any).prototype.toJSON = function (this: Condition.AnyElements<any>): Record<string, any> {
    return {AnyElements: this.condition}
};
(Condition.SizesEquals as any).prototype.toJSON = function (this: Condition.SizesEquals<any>): Record<string, any> {
    return {SizesEquals: this.count}
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
        case "AppendList":
            return new Modification.AppendList(parseObject<Array<any>>(data.AppendList, [Array, type[1]])) as unknown as Modification<T>
        case "AppendSet":
            return new Modification.AppendSet(parseObject<Array<any>>(data.AppendSet, [Array, type[1]])) as unknown as Modification<T>
        case "Remove":
            return new Modification.Remove(parseObject(data.Remove, [Condition, type[1]])) as unknown as Modification<T>
        case "RemoveInstances":
            return new Modification.RemoveInstances(parseObject<Array<any>>(data.RemoveInstances, [Array, type[1]])) as unknown as Modification<T>
        case "DropFirst":
            return new Modification.DropFirst() as unknown as Modification<T>
        case "DropLast":
            return new Modification.DropLast() as unknown as Modification<T>
        case "PerElement":
            return parseObject(data.PerElement, [Modification.PerElement, type[1]])
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
(Modification.AppendList as any).prototype.toJSON = function (this: Modification.AppendList<any>): Record<string, any> {
    return {AppendList: this.items}
};
(Modification.AppendSet as any).prototype.toJSON = function (this: Modification.AppendSet<any>): Record<string, any> {
    return {AppendSet: this.items}
};
(Modification.Remove as any).prototype.toJSON = function (this: Modification.Remove<any>): Record<string, any> {
    return {Remove: this.condition}
};
(Modification.RemoveInstances as any).prototype.toJSON = function (this: Modification.RemoveInstances<any>): Record<string, any> {
    return {RemoveInstances: this.items}
};
(Modification.DropFirst as any).prototype.toJSON = function (this: Modification.DropFirst<any>): Record<string, any> {
    return {DropFirst: true}
};
(Modification.DropLast as any).prototype.toJSON = function (this: Modification.DropLast<any>): Record<string, any> {
    return {DropLast: true}
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

(SortPart as any).prototype.toJSON = function (this: SortPart<any>): string {
    return this.ascending ? this.field : `-${this.field}`
};
(SortPart as any).fromJSON = <T>(value: string, types: Array<ReifiedType>): SortPart<T> => {
    const descending = value.startsWith('-')
    const realName = descending ? value.substring(1) : value
    return new SortPart<T>(realName as (keyof T & string), !descending)
}
