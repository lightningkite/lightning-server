"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.evaluateModification = void 0;
const Condition_1 = require("./Condition");
function evaluateModification(modification, model) {
    const keyAndValue = Object.entries(modification).at(0);
    if (!keyAndValue) {
        throw new Error("Single key expected, received none.");
    }
    const [key, value] = keyAndValue;
    switch (key) {
        case "Assign":
            return value;
        case "Chain":
            let current = model;
            for (const item of value)
                current = evaluateModification(item, current);
            return current;
        case "IfNotNull":
            if (model !== null && model !== undefined) {
                return value;
            }
            return model;
        case "CoerceAtMost":
            if (typeof model === "string" && typeof value == "string") {
                return model < value ? model : value;
            }
            if (typeof model === "number" && typeof value == "number") {
                return Math.min(model, value);
            }
        case "CoerceAtLeast":
            if (typeof model === "string" && typeof value == "string") {
                return model > value ? model : value;
            }
            if (typeof model === "number" && typeof value == "number") {
                return Math.max(model, value);
            }
        case "Increment": {
            const typedValue = value;
            const typedModel = model;
            return (typedModel + typedValue);
        }
        case "Multiply": {
            const typedValue = value;
            const typedModel = model;
            return (typedModel * typedValue);
        }
        case "AppendString": {
            const typedValue = value;
            const typedModel = model;
            return (typedModel + typedValue);
        }
        case "ListAppend":
        case "SetAppend": {
            const typedValue = value;
            const typedModel = model;
            return [...typedModel, ...typedValue];
        }
        case "ListRemove":
        case "SetRemove": {
            const typedValue = value;
            const typedModel = model;
            return typedModel.filter((item) => !(0, Condition_1.evaluateCondition)(typedValue, item));
        }
        case "ListRemoveInstances":
        case "SetRemoveInstances": {
            const typedValue = value;
            const typedModel = model;
            return typedModel.filter((item) => !typedValue.includes(item));
        }
        case "ListDropFirst":
        case "SetDropFirst": {
            const typedValue = value;
            const typedModel = model;
            if (typedValue) {
                return typedModel.slice(1);
            }
        }
        case "ListDropLast":
        case "SetDropLast": {
            const typedModel = model;
            return typedModel.slice(0, -1);
        }
        case "ListPerElement":
        case "SetPerElement": {
            const typedValue = value;
            const typedModel = [...model];
            typedModel.forEach((item, index) => {
                if ((0, Condition_1.evaluateCondition)(typedValue.condition, item)) {
                    typedModel[index] = evaluateModification(typedValue.modification, item);
                }
            });
            return typedModel;
        }
        default:
            const copy = Object.assign({}, model);
            copy[key] = evaluateModification(value, model[key]);
            return copy;
    }
}
exports.evaluateModification = evaluateModification;
//# sourceMappingURL=Modification.js.map