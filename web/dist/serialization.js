"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const Condition_1 = require("./db/Condition");
const khrysalis_runtime_1 = require("@lightningkite/khrysalis-runtime");
Condition_1.Condition.fromJSON = (data, types) => {
    const type = types[0];
    let key = Object.keys(data)[0];
    switch (key) {
        case "Never":
            return new Condition_1.Condition.Never();
        case "Always":
            return new Condition_1.Condition.Always();
        case "And":
            return new Condition_1.Condition.And((0, khrysalis_runtime_1.parseObject)(data.And, [Array, [Condition_1.Condition, type]]));
        case "Or":
            return new Condition_1.Condition.Or((0, khrysalis_runtime_1.parseObject)(data.Or, [Array, [Condition_1.Condition, type]]));
        case "Not":
            return new Condition_1.Condition.Not((0, khrysalis_runtime_1.parseObject)(data.Not, [Condition_1.Condition, type]));
        case "Equal":
            return new Condition_1.Condition.Equal((0, khrysalis_runtime_1.parseObject)(data.Equal, type));
        case "NotEqual":
            return new Condition_1.Condition.NotEqual((0, khrysalis_runtime_1.parseObject)(data.NotEqual, type));
        case "Inside":
            return new Condition_1.Condition.Inside((0, khrysalis_runtime_1.parseObject)(data.Inside, [Array, type]));
        case "NotInside":
            return new Condition_1.Condition.NotInside((0, khrysalis_runtime_1.parseObject)(data.NotInside, [Array, type]));
        case "GreaterThan":
            return new Condition_1.Condition.GreaterThan((0, khrysalis_runtime_1.parseObject)(data.GreaterThan, type));
        case "LessThan":
            return new Condition_1.Condition.LessThan((0, khrysalis_runtime_1.parseObject)(data.LessThan, type));
        case "GreaterThanOrEqual":
            return new Condition_1.Condition.GreaterThanOrEqual((0, khrysalis_runtime_1.parseObject)(data.GreaterThanOrEqual, type));
        case "LessThanOrEqual":
            return new Condition_1.Condition.LessThanOrEqual((0, khrysalis_runtime_1.parseObject)(data.LessThanOrEqual, type));
        case "Search":
            return (0, khrysalis_runtime_1.parseObject)(data.Search, [Condition_1.Condition.Search]);
        case "IntBitsClear":
            return new Condition_1.Condition.IntBitsClear(data.IntBitsClear);
        case "IntBitsSet":
            return new Condition_1.Condition.IntBitsSet(data.IntBitsSet);
        case "IntBitsAnyClear":
            return new Condition_1.Condition.IntBitsAnyClear(data.IntBitsAnyClear);
        case "IntBitsAnySet":
            return new Condition_1.Condition.IntBitsAnySet(data.IntBitsAnySet);
        case "AllElements":
            return new Condition_1.Condition.AllElements((0, khrysalis_runtime_1.parseObject)(data.AllElements, [Condition_1.Condition, type[0]]));
        case "AnyElements":
            return new Condition_1.Condition.AnyElements((0, khrysalis_runtime_1.parseObject)(data.AnyElements, [Condition_1.Condition, type[0]]));
        case "SizesEquals":
            return new Condition_1.Condition.SizesEquals(data.SizesEquals);
        case "Exists":
            return new Condition_1.Condition.Exists(data.Exists);
        case "OnKey":
            return (0, khrysalis_runtime_1.parseObject)(data.OnKey, [Condition_1.Condition.OnKey, type[1]]);
        case "IfNotNull":
            return (0, khrysalis_runtime_1.parseObject)(data.IfNotNull, [Condition_1.Condition, type]);
        default:
            const baseType = type[0];
            const propTypes = baseType.propertyTypes(type.slice(1));
            const innerType = propTypes[key];
            return new Condition_1.Condition.OnField(key, (0, khrysalis_runtime_1.parseObject)(data[key], [Condition_1.Condition, innerType]));
    }
};
Condition_1.Condition.Never.prototype.toJSON = function () {
    return { Never: true };
};
Condition_1.Condition.Always.prototype.toJSON = function () {
    return { Always: true };
};
Condition_1.Condition.And.prototype.toJSON = function () {
    return { And: this.conditions };
};
Condition_1.Condition.Or.prototype.toJSON = function () {
    return { Or: this.conditions };
};
Condition_1.Condition.Not.prototype.toJSON = function () {
    return { Not: this.condition };
};
Condition_1.Condition.Equal.prototype.toJSON = function () {
    return { Equal: this.value };
};
Condition_1.Condition.NotEqual.prototype.toJSON = function () {
    return { NotEqual: this.value };
};
Condition_1.Condition.Inside.prototype.toJSON = function () {
    return { Inside: this.values };
};
Condition_1.Condition.NotInside.prototype.toJSON = function () {
    return { NotInside: this.values };
};
Condition_1.Condition.GreaterThan.prototype.toJSON = function () {
    return { GreaterThan: this.value };
};
Condition_1.Condition.LessThan.prototype.toJSON = function () {
    return { LessThan: this.value };
};
Condition_1.Condition.GreaterThanOrEqual.prototype.toJSON = function () {
    return { GreaterThanOrEqual: this.value };
};
Condition_1.Condition.LessThanOrEqual.prototype.toJSON = function () {
    return { LessThanOrEqual: this.value };
};
Condition_1.Condition.Search.prototype.toJSON = function () {
    return { Search: { value: this.value, ignoreCase: this.ignoreCase } };
};
Condition_1.Condition.IntBitsClear.prototype.toJSON = function () {
    return { IntBitsClear: this.mask };
};
Condition_1.Condition.IntBitsSet.prototype.toJSON = function () {
    return { IntBitsSet: this.mask };
};
Condition_1.Condition.IntBitsAnyClear.prototype.toJSON = function () {
    return { IntBitsAnyClear: this.mask };
};
Condition_1.Condition.IntBitsAnySet.prototype.toJSON = function () {
    return { IntBitsAnySet: this.mask };
};
Condition_1.Condition.AllElements.prototype.toJSON = function () {
    return { AllElements: this.condition };
};
Condition_1.Condition.AnyElements.prototype.toJSON = function () {
    return { AnyElements: this.condition };
};
Condition_1.Condition.SizesEquals.prototype.toJSON = function () {
    return { SizesEquals: this.count };
};
Condition_1.Condition.Exists.prototype.toJSON = function () {
    return { Exists: this.key };
};
Condition_1.Condition.OnKey.prototype.toJSON = function () {
    return { OnKey: { key: this.key, condition: this.condition } };
};
Condition_1.Condition.IfNotNull.prototype.toJSON = function () {
    return { IfNotNull: this.condition };
};
Condition_1.Condition.OnField.prototype.toJSON = function () {
    const result = {};
    result[this.key] = this.condition;
    return result;
};
//# sourceMappingURL=serialization.js.map