"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.xListComparatorGet = exports.SortPart = void 0;
const khrysalis_runtime_1 = require("@lightningkite/khrysalis-runtime");
//! Declares com.lightningkite.lightningdb.SortPart
class SortPart {
    constructor(field, ascending = true) {
        this.field = field;
        this.ascending = ascending;
    }
    static propertyTypes(T) { return { field: [String, T], ascending: [Boolean] }; }
}
exports.SortPart = SortPart;
SortPart.properties = ["field", "ascending"];
(0, khrysalis_runtime_1.setUpDataClass)(SortPart);
//! Declares com.lightningkite.lightningdb.comparator>kotlin.collections.Listcom.lightningkite.lightningdb.SortPartcom.lightningkite.lightningdb.comparator.T
function xListComparatorGet(this_) {
    if (this_.length === 0) {
        return null;
    }
    return (a, b) => {
        for (const part of this_) {
            const result = (0, khrysalis_runtime_1.compareBy)(part.field)(a, b);
            if (!(result === 0)) {
                return part.ascending ? result : (-result);
            }
        }
        return 0;
    };
}
exports.xListComparatorGet = xListComparatorGet;
//# sourceMappingURL=SortPart.js.map