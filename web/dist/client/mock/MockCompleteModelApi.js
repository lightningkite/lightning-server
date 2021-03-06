"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.MockCompleteModelApi = void 0;
const CompleteModelApi_1 = require("../CompleteModelApi");
const MockObserveModelApi_1 = require("./MockObserveModelApi");
const MockReadModelApi_1 = require("./MockReadModelApi");
const MockWriteModelApi_1 = require("./MockWriteModelApi");
//! Declares com.lightningkite.lightningdb.mock.MockCompleteModelApi
class MockCompleteModelApi extends CompleteModelApi_1.CompleteModelApi {
    constructor(table) {
        super();
        this.table = table;
        this.read = new MockReadModelApi_1.MockReadModelApi(this.table);
        this.write = new MockWriteModelApi_1.MockWriteModelApi(this.table);
        this.observe = new MockObserveModelApi_1.MockObserveModelApi(this.table);
    }
}
exports.MockCompleteModelApi = MockCompleteModelApi;
//# sourceMappingURL=MockCompleteModelApi.js.map