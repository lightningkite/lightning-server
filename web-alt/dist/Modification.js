"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.evaluateModification = void 0;
const Condition_1 = require("./Condition");
function evaluateModification(modification, model) {
    const key = Object.keys(modification)[0];
    const value = modification[key];
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
            throw new Error("CoerceAtMost is not supported yet");
        case "CoerceAtLeast":
            throw new Error("CoerceAtLeast is not supported yet");
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
        case "ListAppend": {
            const typedValue = value;
            const typedModel = model;
            return [...typedModel, ...typedValue];
        }
        case "ListRemove": {
            const typedValue = value;
            const typedModel = model;
            return typedModel.filter((item) => !(0, Condition_1.evaluateCondition)(typedValue, item));
        }
        case "ListRemoveInstances": {
            const typedValue = value;
            const typedModel = model;
            return typedModel.filter((item) => !typedValue.includes(item));
        }
        case "ListDropFirst": {
            const typedValue = value;
            const typedModel = model;
            if (typedValue) {
                return typedModel.slice(1);
            }
        }
        case "ListDropLast": {
            const typedValue = value;
            const typedModel = model;
            if (typedValue) {
                return typedModel.slice(0, -1);
            }
        }
        case "ListPerElement": {
            const typedValue = value;
            const typedModel = model;
            typedModel.forEach((item, index) => {
                if ((0, Condition_1.evaluateCondition)(typedValue.condition, item)) {
                    typedModel[index] = evaluateModification(typedValue.modification, item);
                }
            });
            return model;
        }
        case "SetAppend": {
            const typedModel = model;
            const typedValue = value;
            return [...typedModel, ...typedValue];
        }
        case "SetRemove": {
            const typedModel = model;
            const typedValue = value;
            return typedModel.filter((item) => !(0, Condition_1.evaluateCondition)(typedValue, item));
        }
        case "SetRemoveInstances": {
            const typedModel = model;
            const typedValue = value;
            return typedModel.filter((item) => !typedValue.includes(item));
        }
        case "SetDropFirst":
            throw new Error("SetDropFirst is not supported yet");
        case "SetDropLast":
            throw new Error("SetDropLast is not supported yet");
        case "SetPerElement": {
            const typedValue = value;
            const typedModel = model;
            typedModel.forEach((item, index) => {
                if ((0, Condition_1.evaluateCondition)(typedValue.condition, item)) {
                    typedModel[index] = evaluateModification(typedValue.modification, item);
                }
            });
            return model;
        }
        case "Combine":
            throw new Error("Combine is not supported yet");
        case "ModifyByKey": {
            const typedValue = value;
            const typedModel = model;
            const copy = Object.assign({}, typedModel);
            Object.keys(typedValue).forEach((key) => {
                copy[key] = evaluateModification(typedValue[key], copy[key]);
            });
            return copy;
        }
        case "RemoveKeys": {
            const typedValue = value;
            const typedModel = model;
            const copy = Object.assign({}, typedModel);
            typedValue.forEach((key) => {
                delete copy[key];
            });
            return copy;
        }
        default:
            const copy = Object.assign({}, model);
            copy[key] = evaluateModification(value, model[key]);
            return copy;
    }
}
exports.evaluateModification = evaluateModification;
//# sourceMappingURL=Modification.js.map