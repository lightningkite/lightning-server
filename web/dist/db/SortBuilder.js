"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.SortBuilder = exports.sort = void 0;
// Package: com.lightningkite.lightningdb
// Generated by Khrysalis, then edited
const ConditionBuilder_1 = require("./ConditionBuilder");
const SortPart_1 = require("./SortPart");
const khrysalis_runtime_1 = require("@lightningkite/khrysalis-runtime");
//! Declares com.lightningkite.lightningdb.sort
function sort(setup) {
    return (0, khrysalis_runtime_1.also)(new SortBuilder(), (this_) => {
        setup(this_, (0, ConditionBuilder_1.path)());
    }).build();
}
exports.sort = sort;
//! Declares com.lightningkite.lightningdb.SortBuilder
class SortBuilder {
    constructor() {
        this.sortParts = [];
    }
    add(sort) { this.sortParts.push(sort); }
    build() {
        return Array.from(this.sortParts);
    }
    ascending(this_) {
        return new SortPart_1.SortPart(this_, true, undefined);
    }
    descending(this_) {
        return new SortPart_1.SortPart(this_, false, undefined);
    }
    ascendingString(this_, ignoreCase) {
        return new SortPart_1.SortPart(this_, true, ignoreCase);
    }
    descendingString(this_, ignoreCase) {
        return new SortPart_1.SortPart(this_, false, ignoreCase);
    }
}
exports.SortBuilder = SortBuilder;
//# sourceMappingURL=SortBuilder.js.map