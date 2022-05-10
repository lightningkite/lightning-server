"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.MockTable = void 0;
const SignalData_1 = require("../SignalData");
const khrysalis_runtime_1 = require("@lightningkite/khrysalis-runtime");
const iter_tools_es_1 = require("iter-tools-es");
const rxjs_1 = require("rxjs");
const operators_1 = require("rxjs/operators");
//! Declares com.lightningkite.ktordb.mock.MockTable
class MockTable {
    constructor() {
        this.data = new khrysalis_runtime_1.EqualOverrideMap([]);
        this.signals = new rxjs_1.Subject();
    }
    observe(condition) {
        return this.signals.pipe((0, operators_1.map)((it) => (0, iter_tools_es_1.execPipe)(this.data.values(), (0, iter_tools_es_1.filter)((it) => condition.invoke(it)), iter_tools_es_1.toArray)));
    }
    getItem(id) {
        var _a;
        return ((_a = this.data.get(id)) !== null && _a !== void 0 ? _a : null);
    }
    asList() {
        return (0, iter_tools_es_1.toArray)(this.data.values());
    }
    addItem(item) {
        this.data.set(item._id, item);
        this.signals.next(new SignalData_1.SignalData(item, true, false));
        return item;
    }
    replaceItem(item) {
        this.data.set(item._id, item);
        this.signals.next(new SignalData_1.SignalData(item, false, false));
        return item;
    }
    deleteItem(item) {
        this.deleteItemById(item._id);
    }
    deleteItemById(id) {
        var _a;
        const item_16 = ((_a = this.data.get(id)) !== null && _a !== void 0 ? _a : null);
        if (item_16 !== null) {
            this.data.delete(id);
            this.signals.next(new SignalData_1.SignalData(item_16, false, true));
        }
    }
}
exports.MockTable = MockTable;
//# sourceMappingURL=MockTable.js.map