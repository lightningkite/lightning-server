"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.GroupAggregateQuery = exports.AggregateQuery = exports.GroupCountQuery = void 0;
// Package: com.lightningkite.lightningdb
// Generated by Khrysalis - this file will be overwritten.
const Aggregate_1 = require("./Aggregate");
const Condition_1 = require("./Condition");
const DataClassPath_1 = require("./DataClassPath");
const khrysalis_runtime_1 = require("@lightningkite/khrysalis-runtime");
//! Declares com.lightningkite.lightningdb.GroupCountQuery
class GroupCountQuery {
    constructor(condition = new Condition_1.Condition.Always(), groupBy) {
        this.condition = condition;
        this.groupBy = groupBy;
    }
    static propertyTypes(Model) { return { condition: [Condition_1.Condition, Model], groupBy: [DataClassPath_1.DataClassPathPartial, Model] }; }
}
exports.GroupCountQuery = GroupCountQuery;
GroupCountQuery.properties = ["condition", "groupBy"];
(0, khrysalis_runtime_1.setUpDataClass)(GroupCountQuery);
//! Declares com.lightningkite.lightningdb.AggregateQuery
class AggregateQuery {
    constructor(aggregate, condition = new Condition_1.Condition.Always(), property) {
        this.aggregate = aggregate;
        this.condition = condition;
        this.property = property;
    }
    static propertyTypes(Model) { return { aggregate: [Aggregate_1.Aggregate], condition: [Condition_1.Condition, Model], property: [DataClassPath_1.DataClassPathPartial, Model] }; }
}
exports.AggregateQuery = AggregateQuery;
AggregateQuery.properties = ["aggregate", "condition", "property"];
(0, khrysalis_runtime_1.setUpDataClass)(AggregateQuery);
//! Declares com.lightningkite.lightningdb.GroupAggregateQuery
class GroupAggregateQuery {
    constructor(aggregate, condition = new Condition_1.Condition.Always(), groupBy, property) {
        this.aggregate = aggregate;
        this.condition = condition;
        this.groupBy = groupBy;
        this.property = property;
    }
    static propertyTypes(Model) { return { aggregate: [Aggregate_1.Aggregate], condition: [Condition_1.Condition, Model], groupBy: [DataClassPath_1.DataClassPathPartial, Model], property: [DataClassPath_1.DataClassPathPartial, Model] }; }
}
exports.GroupAggregateQuery = GroupAggregateQuery;
GroupAggregateQuery.properties = ["aggregate", "condition", "groupBy", "property"];
(0, khrysalis_runtime_1.setUpDataClass)(GroupAggregateQuery);
//# sourceMappingURL=GroupCountQuery.js.map