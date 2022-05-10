import {Condition} from './Condition'
import {Modification} from './Modification'
import {compareBy, DataClass, parseObject, ReifiedType} from "@lightningkite/khrysalis-runtime";
import {DataClassProperty} from "./DataClassProperty";

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
        case "Search":
            return parseObject(data.Search, [Condition.Search])
        case "IntBitsClear":
            return new Condition.IntBitsClear(data.IntBitsClear as number) as unknown as Condition<T>
        case "IntBitsSet":
            return new Condition.IntBitsSet(data.IntBitsSet as number) as unknown as Condition<T>
        case "IntBitsAnyClear":
            return new Condition.IntBitsAnyClear(data.IntBitsAnyClear as number) as unknown as Condition<T>
        case "IntBitsAnySet":
            return new Condition.IntBitsAnySet(data.IntBitsAnySet as number) as unknown as Condition<T>
        case "AllElements":
            return new Condition.AllElements(parseObject(data.AllElements, [Condition, type[0]])) as unknown as Condition<T>
        case "AnyElements":
            return new Condition.AnyElements(parseObject(data.AnyElements, [Condition, type[0]])) as unknown as Condition<T>
        case "SizesEquals":
            return new Condition.SizesEquals(data.SizesEquals as number) as unknown as Condition<T>
        case "Exists":
            return new Condition.Exists(data.Exists as string) as unknown as Condition<T>
        case "OnKey":
            return parseObject(data.OnKey, [Condition.OnKey, type[1]])
        case "IfNotNull":
            return parseObject(data.IfNotNull, [Condition, type])
        default:
            const baseType = type[0]
            const propTypes = baseType.propertyTypes(type.slice(1))
            const innerType = propTypes[key]
            return new Condition.OnField(key as DataClassProperty<T, unknown>, parseObject(data[key], [Condition, innerType]))
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
(Condition.Search as any).prototype.toJSON = function (this: Condition.Search): Record<string, any> {
    return {Search: {value: this.value, ignoreCase: this.ignoreCase}}
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