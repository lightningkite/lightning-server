"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.Query = void 0;
// Package: com.lightningkite.ktordb
// Generated by Khrysalis - this file will be overwritten.
const Condition_1 = require("./Condition");
const SortPart_1 = require("./SortPart");
const khrysalis_runtime_1 = require("@lightningkite/khrysalis-runtime");
//! Declares com.lightningkite.ktordb.Query
class Query {
    constructor(condition = new Condition_1.Condition.Always(), orderBy = [], skip = 0, limit = 100) {
        this.condition = condition;
        this.orderBy = orderBy;
        this.skip = skip;
        this.limit = limit;
    }
    static propertyTypes(T) { return { condition: [Condition_1.Condition, T], orderBy: [Array, [SortPart_1.SortPart, T]], skip: [Number], limit: [Number] }; }
}
exports.Query = Query;
Query.properties = ["condition", "orderBy", "skip", "limit"];
(0, khrysalis_runtime_1.setUpDataClass)(Query);
//# sourceMappingURL=Query.js.map