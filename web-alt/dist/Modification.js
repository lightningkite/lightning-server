"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.evaluateModification = void 0;
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
        case "AppendList":
            throw new Error("AppendList is not supported yet");
        case "AppendSet":
            throw new Error("AppendSet is not supported yet");
        case "Remove":
            throw new Error("Remove is not supported yet");
        case "RemoveInstances":
            throw new Error("RemoveInstances is not supported yet");
        case "DropFirst":
            throw new Error("DropFirst is not supported yet");
        case "DropLast":
            throw new Error("DropLast is not supported yet");
        case "PerElement":
            throw new Error("PerElement is not supported yet");
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