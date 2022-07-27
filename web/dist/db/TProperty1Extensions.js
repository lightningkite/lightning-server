"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.keySet = exports.keyGet = void 0;
function keyGet(on, key) {
    return on[key];
}
exports.keyGet = keyGet;
function keySet(on, key, value) {
    const dict = {};
    dict[key] = value;
    return on.copy(dict);
}
exports.keySet = keySet;
//# sourceMappingURL=TProperty1Extensions.js.map