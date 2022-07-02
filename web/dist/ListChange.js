"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.xEntryChangeListChange = exports.ListChange = void 0;
const khrysalis_runtime_1 = require("@lightningkite/khrysalis-runtime");
//! Declares com.lightningkite.lightningdb.ListChange
class ListChange {
    constructor(wholeList = null, old = null, _new = null) {
        this.wholeList = wholeList;
        this.old = old;
        this._new = _new;
    }
    static propertyTypes(T) { return { wholeList: [Array, T], old: T, _new: T }; }
}
exports.ListChange = ListChange;
ListChange.properties = ["wholeList", "old", "_new"];
(0, khrysalis_runtime_1.setUpDataClass)(ListChange);
//! Declares com.lightningkite.lightningdb.listChange>com.lightningkite.lightningdb.EntryChangecom.lightningkite.lightningdb.listChange.T
function xEntryChangeListChange(this_) {
    return new ListChange(undefined, this_.old, this_._new);
}
exports.xEntryChangeListChange = xEntryChangeListChange;
//# sourceMappingURL=ListChange.js.map