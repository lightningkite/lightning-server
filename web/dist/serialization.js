"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const Condition_1 = require("./Condition");
const khrysalis_1 = require("../../../khrysalis");
Condition_1.Condition.fromJSON = (data, type) => {
    let key = Object.keys(data)[0];
    switch (key) {
        case "Never":
            return new Condition_1.Condition.Never();
        case "Always":
            return new Condition_1.Condition.Always();
        case "And":
            return new Condition_1.Condition.And((0, khrysalis_1.parseObject)(data.And, [Array, [Condition_1.Condition, type]]));
        case "Or":
            return new Condition_1.Condition.Or((0, khrysalis_1.parseObject)(data.Or, [Array, [Condition_1.Condition, type]]));
        case "Not":
            return new Condition_1.Condition.Not((0, khrysalis_1.parseObject)(data.Not, [Condition_1.Condition, type]));
        case "Equal":
            return new Condition_1.Condition.Equal((0, khrysalis_1.parseObject)(data.Equal, type));
        case "NotEqual":
            return new Condition_1.Condition.NotEqual((0, khrysalis_1.parseObject)(data.NotEqual, type));
        case "Inside":
            return new Condition_1.Condition.Inside((0, khrysalis_1.parseObject)(data.Inside, [Array, type]));
        case "NotInside":
            return new Condition_1.Condition.NotInside((0, khrysalis_1.parseObject)(data.NotInside, [Array, type]));
        case "GreaterThan":
            return new Condition_1.Condition.GreaterThan((0, khrysalis_1.parseObject)(data.GreaterThan, type));
        case "LessThan":
            return new Condition_1.Condition.LessThan((0, khrysalis_1.parseObject)(data.LessThan, type));
        case "GreaterThanOrEqual":
            return new Condition_1.Condition.GreaterThanOrEqual((0, khrysalis_1.parseObject)(data.GreaterThanOrEqual, type));
        case "LessThanOrEqual":
            return new Condition_1.Condition.LessThanOrEqual((0, khrysalis_1.parseObject)(data.LessThanOrEqual, type));
        case "Search":
            return (0, khrysalis_1.parseObject)(data.Search, [Condition_1.Condition.Search]);
        case "IntBitsClear":
            return new Condition_1.Condition.IntBitsClear(data.IntBitsClear);
        case "IntBitsSet":
            return new Condition_1.Condition.IntBitsSet(data.IntBitsSet);
        case "IntBitsAnyClear":
            return new Condition_1.Condition.IntBitsAnyClear(data.IntBitsAnyClear);
        case "IntBitsAnySet":
            return new Condition_1.Condition.IntBitsAnySet(data.IntBitsAnySet);
        case "AllElements":
            return new Condition_1.Condition.AllElements((0, khrysalis_1.parseObject)(data.AllElements, [Condition_1.Condition, type[0]]));
        case "AnyElements":
            return new Condition_1.Condition.AnyElements((0, khrysalis_1.parseObject)(data.AnyElements, [Condition_1.Condition, type[0]]));
        case "SizesEquals":
            return new Condition_1.Condition.SizesEquals(data.SizesEquals);
        case "Exists":
            return new Condition_1.Condition.Exists(data.Exists);
        case "OnKey":
            return (0, khrysalis_1.parseObject)(data.OnKey, [Condition_1.Condition.OnKey, type[1]]);
        case "IfNotNull":
            return (0, khrysalis_1.parseObject)(data.IfNotNull, [Condition_1.Condition, type]);
        default:
            const baseType = type[0];
            baseType;
            return new Condition_1.Condition.OnField(key, (0, khrysalis_1.parseObject)(data[key], [Condition_1.Condition,]));
    }
};
;
Condition_1.Condition.Never.toJSON = function () { return { Never: true }; };
Condition_1.Condition.Always.toJSON = function () { return { Always: true }; };
Condition_1.Condition.And.toJSON = function () { return { And: this.conditions }; };
Condition_1.Condition.Or.toJSON = function () { return { Or: this.conditions }; };
Condition_1.Condition.Not.toJSON = function () { return { Not: this.condition }; };
Condition_1.Condition.Equal.toJSON = function () { return { Equal: this.value }; };
Condition_1.Condition.NotEqual.toJSON = function () { return { NotEqual: this.value }; };
Condition_1.Condition.Inside.toJSON = function () { return { Inside: this.values }; };
Condition_1.Condition.NotInside.toJSON = function () { return { NotInside: this.values }; };
Condition_1.Condition.GreaterThan.toJSON = function () { return { GreaterThan: this.value }; };
Condition_1.Condition.LessThan.toJSON = function () { return { LessThan: this.value }; };
Condition_1.Condition.GreaterThanOrEqual.toJSON = function () { return { GreaterThanOrEqual: this.value }; };
Condition_1.Condition.LessThanOrEqual.toJSON = function () { return { LessThanOrEqual: this.value }; };
Condition_1.Condition.Search.toJSON = function () { return { Search: { value: this.value, ignoreCase: this.ignoreCase } }; };
Condition_1.Condition.IntBitsClear.toJSON = function () { return { IntBitsClear: this.mask }; };
Condition_1.Condition.IntBitsSet.toJSON = function () { return { IntBitsSet: this.mask }; };
Condition_1.Condition.IntBitsAnyClear.toJSON = function () { return { IntBitsAnyClear: this.mask }; };
Condition_1.Condition.IntBitsAnySet.toJSON = function () { return { IntBitsAnySet: this.mask }; };
Condition_1.Condition.AllElements.toJSON = function () { return { AllElements: this.condition }; };
Condition_1.Condition.AnyElements.toJSON = function () { return { AnyElements: this.condition }; };
Condition_1.Condition.SizesEquals.toJSON = function () { return { SizesEquals: this.count }; };
Condition_1.Condition.Exists.toJSON = function () { return { Exists: this.key }; };
Condition_1.Condition.OnKey.toJSON = function () { return { OnKey: { key: this.key, condition: this.condition } }; };
Condition_1.Condition.IfNotNull.toJSON = function () { return { IfNotNull: this.condition }; };
Condition_1.Condition.OnField.toJSON = function () {
    const result = {};
    result[this.key.name] = this.condition;
    return result;
};
//# sourceMappingURL=serialization.js.map