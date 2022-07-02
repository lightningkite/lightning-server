"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.MockReadModelApi = void 0;
const SortPart_1 = require("../../shared/SortPart");
const ReadModelApi_1 = require("../ReadModelApi");
const ItemNotFound_1 = require("./ItemNotFound");
const khrysalis_runtime_1 = require("@lightningkite/khrysalis-runtime");
const rxjs_1 = require("rxjs");
//! Declares com.lightningkite.lightningdb.mock.MockReadModelApi
class MockReadModelApi extends ReadModelApi_1.ReadModelApi {
    constructor(table) {
        super();
        this.table = table;
    }
    list(query) {
        var _a;
        return (0, rxjs_1.of)(this.table
            .asList().filter((item) => query.condition.invoke(item)).slice().sort((_a = (0, SortPart_1.xListComparatorGet)(query.orderBy)) !== null && _a !== void 0 ? _a : (0, khrysalis_runtime_1.compareBy)((it) => it._id)).slice(query.skip).slice(0, query.limit));
    }
    get(id) {
        var _a;
        return (_a = (() => {
            const temp9 = this.table.getItem(id);
            if (temp9 === null) {
                return null;
            }
            return ((it) => (0, rxjs_1.of)(it))(temp9);
        })()) !== null && _a !== void 0 ? _a : (0, rxjs_1.throwError)(new ItemNotFound_1.ItemNotFound(`404 item with key ${id} not found`));
    }
}
exports.MockReadModelApi = MockReadModelApi;
//# sourceMappingURL=MockReadModelApi.js.map