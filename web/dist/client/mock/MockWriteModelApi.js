"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.MockWriteModelApi = void 0;
const WriteModelApi_1 = require("../WriteModelApi");
const ItemNotFound_1 = require("./ItemNotFound");
const rxjs_1 = require("rxjs");
//! Declares com.lightningkite.ktordb.mock.MockWriteModelApi
class MockWriteModelApi extends WriteModelApi_1.WriteModelApi {
    constructor(table) {
        super();
        this.table = table;
    }
    post(value) {
        return (0, rxjs_1.of)(this.table.addItem(value));
    }
    postBulk(values) {
        return (0, rxjs_1.of)(values.map((it) => this.table.addItem(it)));
    }
    put(value) {
        return (0, rxjs_1.of)(this.table.replaceItem(value));
    }
    putBulk(values) {
        return (0, rxjs_1.of)(values.map((it) => this.table.replaceItem(it)));
    }
    patch(id, modification) {
        var _a;
        return (_a = (() => {
            var _a;
            const temp6 = ((_a = this.table.data.get(id)) !== null && _a !== void 0 ? _a : null);
            if (temp6 === null) {
                return null;
            }
            return ((item) => {
                const modified = modification.invoke(item);
                this.table.replaceItem(modified);
                return (0, rxjs_1.of)(modified);
            })(temp6);
        })()) !== null && _a !== void 0 ? _a : (0, rxjs_1.throwError)(new ItemNotFound_1.ItemNotFound(`404 item with key ${id} not found`));
    }
    patchBulk(modification) {
        return (0, rxjs_1.of)(this.table
            .asList().filter((it) => modification.condition.invoke(it)).map((it) => this.table.replaceItem(modification.modification.invoke(it))));
    }
    _delete(id) {
        return (0, rxjs_1.of)(this.table.deleteItemById(id));
    }
    deleteBulk(condition) {
        return (0, rxjs_1.of)(this.table
            .asList().filter((it) => condition.invoke(it)).forEach((it) => {
            this.table.deleteItem(it);
        }));
    }
}
exports.MockWriteModelApi = MockWriteModelApi;
//# sourceMappingURL=MockWriteModelApi.js.map