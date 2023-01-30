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
        case "Increment":
            throw new Error("Increment is not supported yet");
        case "Multiply":
            throw new Error("Multiply is not supported yet");
        case "AppendString":
            throw new Error("AppendString is not supported yet");
        case "ListAppend":
            throw new Error("ListAppend is not supported yet");
        case "ListRemove":
            throw new Error("ListRemove is not supported yet");
        case "ListRemoveInstances":
            throw new Error("ListRemoveInstances is not supported yet");
        case "ListDropFirst":
            throw new Error("ListDropFirst is not supported yet");
        case "ListDropLast":
            throw new Error("ListDropLast is not supported yet");
        case "ListPerElement":
            const typedValue = value;
            const typedModel = model;
            typedModel.forEach((item, index) => {
                if ((0, Condition_1.evaluateCondition)(typedValue.condition, item)) {
                    typedModel[index] = evaluateModification(typedValue.modification, item);
                }
            });
            return model;
        case "SetAppend":
            throw new Error("SetAppend is not supported yet");
        case "SetRemove":
            throw new Error("SetRemove is not supported yet");
        case "SetRemoveInstances":
            throw new Error("SetRemoveInstances is not supported yet");
        case "SetDropFirst":
            throw new Error("SetDropFirst is not supported yet");
        case "SetDropLast":
            throw new Error("SetDropLast is not supported yet");
        case "SetPerElement":
            throw new Error("SetPerElement is not supported yet");
        case "Combine":
            throw new Error("Combine is not supported yet");
        case "ModifyByKey":
            throw new Error("ModifyByKey is not supported yet");
        case "RemoveKeys":
            throw new Error("RemoveKeys is not supported yet");
        default:
            const copy = Object.assign({}, model);
            copy[key] = evaluateModification(value, model[key]);
            return copy;
    }
}
exports.evaluateModification = evaluateModification;
//# sourceMappingURL=Modification.js.map