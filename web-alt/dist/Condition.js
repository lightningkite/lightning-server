"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.or = exports.and = exports.condition = exports.evaluateCondition = void 0;
function evaluateCondition(condition, model) {
    const key = Object.keys(condition)[0];
    const value = condition[key];
    switch (key) {
        case "Never":
            return false;
        case "Always":
            return true;
        case "And":
            return value.every(x => evaluateCondition(x, model));
        case "Or":
            return value.some(x => evaluateCondition(x, model));
        case "Not":
            return !evaluateCondition(value, model);
        case "Equal":
            return model === value;
        case "NotEqual":
            return model !== value;
        case "Inside":
            return value.indexOf(model) !== -1;
        case "NotInside":
            return value.indexOf(model) === -1;
        case "GreaterThan":
            return model > value;
        case "LessThan":
            return model < value;
        case "GreaterThanOrEqual":
            return model >= value;
        case "LessThanOrEqual":
            return model <= value;
        case "StringContains":
            const v = value;
            if (v.ignoreCase)
                return (model.toLowerCase().indexOf(v.value) !== -1);
            else
                return (model.indexOf(v.value) !== -1);
        case "FullTextSearch":
            const v2 = value;
            if (v2.ignoreCase)
                return (model.toLowerCase().indexOf(v2.value) !== -1);
            else
                return (model.indexOf(v2.value) !== -1);
        case "IntBitsClear":
            return (model & value) === 0;
        case "IntBitsSet":
            return (model & value) === value;
        case "IntBitsAnyClear":
            return (model & value) < value;
        case "IntBitsAnySet":
            return (model & value) > 0;
        case "ListAllElements":
            return model.every(x => evaluateCondition(value, x));
        case "ListAnyElements":
            return model.some(x => evaluateCondition(value, x));
        case "ListSizesEquals":
            return model.length === value;
        case "SetAllElements":
            return Array.from(model).every(x => evaluateCondition(value, x));
        case "SetAnyElements":
            return Array.from(model).some(x => evaluateCondition(value, x));
        case "SetSizesEquals":
            return model.size === value;
        case "Exists":
            return true;
        case "IfNotNull":
            return model !== null && model !== undefined && evaluateCondition(value, model);
        default:
            return evaluateCondition(value, model[key]);
    }
}
exports.evaluateCondition = evaluateCondition;
function condition(key, value) {
    const parts = key.split(':');
    const path = parts[0];
    const comparison = parts[1];
    const pathParts = path.split('.');
    let current = {};
    current[comparison] = value;
    for (const part of pathParts.reverse()) {
        const past = current;
        current = {};
        current[part] = past;
    }
    return current;
}
exports.condition = condition;
function and(conditionBuilder) {
    if (Array.isArray(conditionBuilder))
        return { And: conditionBuilder };
    let subconditions = [];
    for (const key in conditionBuilder) {
        if (key.length === 0) {
            subconditions.push(conditionBuilder[""]);
            continue;
        }
        subconditions.push(condition(key, conditionBuilder[key]));
    }
    if (subconditions.length == 1)
        return subconditions[0];
    else if (subconditions.length == 0)
        return { "Always": true };
    else
        return { "And": subconditions };
}
exports.and = and;
function or(conditionBuilder) {
    if (Array.isArray(conditionBuilder))
        return { Or: conditionBuilder };
    let subconditions = [];
    for (const key in conditionBuilder) {
        if (key.length === 0) {
            subconditions.push(conditionBuilder[""]);
            continue;
        }
        subconditions.push(condition(key, conditionBuilder[key]));
    }
    if (subconditions.length == 1)
        return subconditions[0];
    else if (subconditions.length == 0)
        return { "Always": true };
    else
        return { "Or": subconditions };
}
exports.or = or;
//# sourceMappingURL=Condition.js.map