"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const Condition_1 = require("./Condition");
const Modification_1 = require("./Modification");
const khrysalis_runtime_1 = require("@lightningkite/khrysalis-runtime");
const SortPart_1 = require("./SortPart");
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
        case "StringContains":
            return (0, khrysalis_runtime_1.parseObject)(data.StringContains, [Condition_1.Condition.StringContains]);
        case "FullTextSearch":
            return (0, khrysalis_runtime_1.parseObject)(data.FullTextSearch, [Condition_1.Condition.FullTextSearch]);
        case "IntBitsClear":
            return new Condition_1.Condition.IntBitsClear(data.IntBitsClear);
        case "IntBitsSet":
            return new Condition_1.Condition.IntBitsSet(data.IntBitsSet);
        case "IntBitsAnyClear":
            return new Condition_1.Condition.IntBitsAnyClear(data.IntBitsAnyClear);
        case "IntBitsAnySet":
            return new Condition_1.Condition.IntBitsAnySet(data.IntBitsAnySet);
        case "ListAllElements":
            return new Condition_1.Condition.ListAllElements((0, khrysalis_runtime_1.parseObject)(data.ListAllElements, [Condition_1.Condition, type[1]]));
        case "ListAnyElements":
            return new Condition_1.Condition.ListAnyElements((0, khrysalis_runtime_1.parseObject)(data.ListAnyElements, [Condition_1.Condition, type[1]]));
        case "ListSizesEquals":
            return new Condition_1.Condition.ListSizesEquals(data.ListSizesEquals);
        case "SetAllElements":
            return new Condition_1.Condition.SetAllElements((0, khrysalis_runtime_1.parseObject)(data.SetAllElements, [Condition_1.Condition, type[1]]));
        case "SetAnyElements":
            return new Condition_1.Condition.SetAnyElements((0, khrysalis_runtime_1.parseObject)(data.SetAnyElements, [Condition_1.Condition, type[1]]));
        case "SetSizesEquals":
            return new Condition_1.Condition.SetSizesEquals(data.SetSizesEquals);
        case "Exists":
            return new Condition_1.Condition.Exists(data.Exists);
        case "OnKey":
            return (0, khrysalis_runtime_1.parseObject)(data.OnKey, [Condition_1.Condition.OnKey, type[2]]);
        case "IfNotNull":
            return new Condition_1.Condition.IfNotNull((0, khrysalis_runtime_1.parseObject)(data.IfNotNull, [Condition_1.Condition, type]));
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
Condition_1.Condition.StringContains.prototype.toJSON = function () {
    return { StringContains: { value: this.value, ignoreCase: this.ignoreCase } };
};
Condition_1.Condition.FullTextSearch.prototype.toJSON = function () {
    return { FullTextSearch: { value: this.value, ignoreCase: this.ignoreCase } };
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
Condition_1.Condition.ListAllElements.prototype.toJSON = function () {
    return { ListAllElements: this.condition };
};
Condition_1.Condition.ListAnyElements.prototype.toJSON = function () {
    return { ListAnyElements: this.condition };
};
Condition_1.Condition.ListSizesEquals.prototype.toJSON = function () {
    return { ListSizesEquals: this.count };
};
Condition_1.Condition.SetAllElements.prototype.toJSON = function () {
    return { SetAllElements: this.condition };
};
Condition_1.Condition.SetAnyElements.prototype.toJSON = function () {
    return { SetAnyElements: this.condition };
};
Condition_1.Condition.SetSizesEquals.prototype.toJSON = function () {
    return { SetSizesEquals: this.count };
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
Modification_1.Modification.fromJSON = (data, types) => {
    const type = types[0];
    let key = Object.keys(data)[0];
    switch (key) {
        case "Chain":
            return new Modification_1.Modification.Chain((0, khrysalis_runtime_1.parseObject)(data.Chain, [Array, [Modification_1.Modification, type]]));
        case "IfNotNull":
            return new Modification_1.Modification.IfNotNull((0, khrysalis_runtime_1.parseObject)(data.IfNotNull, [Modification_1.Modification, type]));
        case "Assign":
            return new Modification_1.Modification.Assign((0, khrysalis_runtime_1.parseObject)(data.Assign, type));
        case "CoerceAtMost":
            return new Modification_1.Modification.CoerceAtMost((0, khrysalis_runtime_1.parseObject)(data.CoerceAtMost, type));
        case "CoerceAtLeast":
            return new Modification_1.Modification.CoerceAtLeast((0, khrysalis_runtime_1.parseObject)(data.CoerceAtLeast, type));
        case "Increment":
            return new Modification_1.Modification.Increment(data.Increment);
        case "Multiply":
            return new Modification_1.Modification.Multiply(data.Multiply);
        case "AppendString":
            return new Modification_1.Modification.AppendString(data.AppendString);
        case "ListAppend":
            return new Modification_1.Modification.ListAppend((0, khrysalis_runtime_1.parseObject)(data.ListAppend, [Array, type[1]]));
        case "ListRemove":
            return new Modification_1.Modification.ListRemove((0, khrysalis_runtime_1.parseObject)(data.ListRemove, [Condition_1.Condition, type[1]]));
        case "ListRemoveInstances":
            return new Modification_1.Modification.ListRemoveInstances((0, khrysalis_runtime_1.parseObject)(data.ListRemoveInstances, [Array, type[1]]));
        case "ListDropFirst":
            return new Modification_1.Modification.ListDropFirst();
        case "ListDropLast":
            return new Modification_1.Modification.ListDropLast();
        case "SetAppend":
            return new Modification_1.Modification.SetAppend((0, khrysalis_runtime_1.parseObject)(data.SetAppend, [Array, type[1]]));
        case "SetRemove":
            return new Modification_1.Modification.SetRemove((0, khrysalis_runtime_1.parseObject)(data.SetRemove, [Condition_1.Condition, type[1]]));
        case "SetRemoveInstances":
            return new Modification_1.Modification.SetRemoveInstances((0, khrysalis_runtime_1.parseObject)(data.SetRemoveInstances, [Array, type[1]]));
        case "SetDropFirst":
            return new Modification_1.Modification.SetDropFirst();
        case "SetDropLast":
            return new Modification_1.Modification.SetDropLast();
        case "ListPerElement":
            return (0, khrysalis_runtime_1.parseObject)(data.PerElement, [Modification_1.Modification.ListPerElement, type[1]]);
        case "SetPerElement":
            return (0, khrysalis_runtime_1.parseObject)(data.PerElement, [Modification_1.Modification.SetPerElement, type[1]]);
        case "Combine":
            return new Modification_1.Modification.Combine((0, khrysalis_runtime_1.parseObject)(data.Combine, [Map, [String], type[2]]));
        case "ModifyByKey":
            return new Modification_1.Modification.ModifyByKey((0, khrysalis_runtime_1.parseObject)(data.ModifyByKey, [Map, [String], [Modification_1.Modification, type[2]]]));
        case "RemoveKeys":
            return new Modification_1.Modification.RemoveKeys((0, khrysalis_runtime_1.parseObject)(data.RemoveKeys, [Set, [String]]));
        default:
            const baseType = type[0];
            const propTypes = baseType.propertyTypes(type.slice(1));
            const innerType = propTypes[key];
            return new Modification_1.Modification.OnField(key, (0, khrysalis_runtime_1.parseObject)(data[key], [Modification_1.Modification, innerType]));
    }
};
Modification_1.Modification.Chain.prototype.toJSON = function () {
    return { Chain: this.modifications };
};
Modification_1.Modification.IfNotNull.prototype.toJSON = function () {
    return { IfNotNull: this.modification };
};
Modification_1.Modification.Assign.prototype.toJSON = function () {
    return { Assign: this.value };
};
Modification_1.Modification.CoerceAtMost.prototype.toJSON = function () {
    return { CoerceAtMost: this.value };
};
Modification_1.Modification.CoerceAtLeast.prototype.toJSON = function () {
    return { CoerceAtLeast: this.value };
};
Modification_1.Modification.Increment.prototype.toJSON = function () {
    return { Increment: this.by };
};
Modification_1.Modification.Multiply.prototype.toJSON = function () {
    return { Multiply: this.by };
};
Modification_1.Modification.AppendString.prototype.toJSON = function () {
    return { AppendString: this.value };
};
Modification_1.Modification.ListAppend.prototype.toJSON = function () {
    return { ListAppend: this.items };
};
Modification_1.Modification.ListRemove.prototype.toJSON = function () {
    return { ListRemove: this.condition };
};
Modification_1.Modification.ListRemoveInstances.prototype.toJSON = function () {
    return { ListRemoveInstances: this.items };
};
Modification_1.Modification.ListDropFirst.prototype.toJSON = function () {
    return { ListDropFirst: true };
};
Modification_1.Modification.ListDropLast.prototype.toJSON = function () {
    return { ListDropLast: true };
};
Modification_1.Modification.SetAppend.prototype.toJSON = function () {
    return { SetAppend: this.items };
};
Modification_1.Modification.SetRemove.prototype.toJSON = function () {
    return { SetRemove: this.condition };
};
Modification_1.Modification.SetRemoveInstances.prototype.toJSON = function () {
    return { SetRemoveInstances: this.items };
};
Modification_1.Modification.SetDropFirst.prototype.toJSON = function () {
    return { SetDropFirst: true };
};
Modification_1.Modification.SetDropLast.prototype.toJSON = function () {
    return { SetDropLast: true };
};
Modification_1.Modification.Combine.prototype.toJSON = function () {
    return { Combine: this.map };
};
Modification_1.Modification.ModifyByKey.prototype.toJSON = function () {
    return { ModifyByKey: this.map };
};
Modification_1.Modification.RemoveKeys.prototype.toJSON = function () {
    return { RemoveKeys: this.fields };
};
Modification_1.Modification.OnField.prototype.toJSON = function () {
    const result = {};
    result[this.key] = this.modification;
    return result;
};
SortPart_1.SortPart.prototype.toJSON = function () {
    return this.ascending ? this.field : `-${this.field}`;
};
SortPart_1.SortPart.fromJSON = (value, types) => {
    const descending = value.startsWith('-');
    const realName = descending ? value.substring(1) : value;
    return new SortPart_1.SortPart(realName, !descending);
};
//# sourceMappingURL=serialization.js.map