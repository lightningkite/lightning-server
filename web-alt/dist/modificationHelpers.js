"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.areObjectsSame = exports.areArraysSame = exports.areValuesSame = exports.makeObjectModification = void 0;
// Javascript values that can safely be compared with the === operator
const canCompareWithEquals = [
    "string",
    "number",
    "bigint",
    "boolean",
    "symbol",
    "undefined",
];
/**
 * @param oldObject the original object
 * @param newObject partial object, representing the values of fields that might have changed
 * @returns a modification to transform oldObject into newObject. If no changes are needed, returns null. Modifications will only affect "root" fields of the object, not nested fields.
 *
 * Example:
 *
 * makeObjectModification( \
 * 	{foo: 1, bar: 2, fizz: 3, buzz: 4},}, \
 * 	{foo: 1, bar: 0, buzz: 0} \
 * )
 *
 * returns: {Chain: [{bar: {Assign: 0}}, {buzz: {Assign: 0}}]}
 */
function makeObjectModification(oldObject, newObject) {
    const modifications = [];
    Object.keys(newObject).forEach((k) => {
        const key = k;
        const oldValue = oldObject[key];
        let newValue = newObject[key];
        if (newValue === undefined) {
            newValue = null;
        }
        if (!(0, exports.areValuesSame)(oldValue, newValue)) {
            modifications.push({
                [key]: { Assign: newValue },
            });
        }
    });
    return modifications.length > 0 ? { Chain: modifications } : null;
}
exports.makeObjectModification = makeObjectModification;
/**
 * Recursively compares two javascript values, including nested object properties and arrays.
 */
const areValuesSame = (a, b) => {
    if (typeof a !== typeof b) {
        return false;
    }
    const types = typeof a;
    if (a === b) {
        return true;
    }
    if (canCompareWithEquals.includes(types)) {
        return false;
    }
    if (Array.isArray(a) && Array.isArray(b)) {
        return (0, exports.areArraysSame)(a, b, exports.areValuesSame);
    }
    if (types === "object" && a !== null && b !== null) {
        return (0, exports.areObjectsSame)(a, b, exports.areValuesSame);
    }
    return false;
};
exports.areValuesSame = areValuesSame;
/**
 *
 * @param array1
 * @param array2
 * @param areSame (optional) a function that compares two values of the array's type. Defaults to ===.
 * @returns true if the arrays are the same length and contain the same values in the same order.
 */
const areArraysSame = (array1, array2, areSame = (a, b) => a === b) => {
    if (array1.length !== array2.length) {
        return false;
    }
    return array1.every((item1, index) => areSame(item1, array2[index]));
};
exports.areArraysSame = areArraysSame;
/**
 *
 * @param object1
 * @param object2
 * @param areSame (optional) a function that compares two values of the object's type. Defaults to ===.
 * @returns true if the objects have the same keys and values.
 */
const areObjectsSame = (object1, object2, areSame = (a, b) => a === b) => {
    const keys1 = Object.keys(object1);
    const keys2 = Object.keys(object2);
    if (keys1.length !== keys2.length) {
        return false;
    }
    return keys1.every((key) => areSame(object1[key], object2[key]));
};
exports.areObjectsSame = areObjectsSame;
//# sourceMappingURL=modificationHelpers.js.map