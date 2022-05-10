"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.SignalData = void 0;
const khrysalis_runtime_1 = require("@lightningkite/khrysalis-runtime");
//! Declares com.lightningkite.ktordb.SignalData
class SignalData {
    constructor(item, created, deleted) {
        this.item = item;
        this.created = created;
        this.deleted = deleted;
    }
    static propertyTypes(Model) { return { item: Model, created: [Boolean], deleted: [Boolean] }; }
}
exports.SignalData = SignalData;
SignalData.properties = ["item", "created", "deleted"];
(0, khrysalis_runtime_1.setUpDataClass)(SignalData);
//# sourceMappingURL=SignalData.js.map