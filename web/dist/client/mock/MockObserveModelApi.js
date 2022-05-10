"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.MockObserveModelApi = void 0;
const ObserveModelApi_1 = require("../ObserveModelApi");
const rxjs_1 = require("rxjs");
//! Declares com.lightningkite.ktordb.mock.MockObserveModelApi
class MockObserveModelApi extends ObserveModelApi_1.ObserveModelApi {
    constructor(table) {
        super();
        this.table = table;
    }
    observe(query) {
        return (0, rxjs_1.concat)((0, rxjs_1.of)(this.table.asList().filter((item) => query.condition.invoke(item))), this.table.observe(query.condition));
    }
}
exports.MockObserveModelApi = MockObserveModelApi;
//# sourceMappingURL=MockObserveModelApi.js.map