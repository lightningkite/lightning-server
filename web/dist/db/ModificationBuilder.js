"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ModificationBuilder = exports.xModificationAnd = exports.modification = void 0;
// Package: com.lightningkite.lightningdb
// Generated by Khrysalis - this file will be overwritten.
const Condition_1 = require("./Condition");
const ConditionBuilder_1 = require("./ConditionBuilder");
const Modification_1 = require("./Modification");
const khrysalis_runtime_1 = require("@lightningkite/khrysalis-runtime");
const iter_tools_es_1 = require("iter-tools-es");
//! Declares com.lightningkite.lightningdb.modification
function modification(setup) {
    return (0, khrysalis_runtime_1.also)(new ModificationBuilder(), (this_) => {
        setup(this_, (0, ConditionBuilder_1.path)());
    }).build();
}
exports.modification = modification;
//! Declares com.lightningkite.lightningdb.and>com.lightningkite.lightningdb.Modificationcom.lightningkite.lightningdb.and.T
function xModificationAnd(this_, setup) {
    return (0, khrysalis_runtime_1.also)(new ModificationBuilder(), (this_1) => {
        this_1.modifications.push(this_);
        setup(this_1, (0, ConditionBuilder_1.path)());
    }).build();
}
exports.xModificationAnd = xModificationAnd;
//! Declares com.lightningkite.lightningdb.ModificationBuilder
class ModificationBuilder {
    constructor() {
        this.modifications = [];
    }
    add(modification) { this.modifications.push(modification); }
    build() {
        if (this.modifications.length === 1) {
            return this.modifications[0];
        }
        else {
            return new Modification_1.Modification.Chain(this.modifications);
        }
    }
    assign(this_, value) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.Assign(value)));
    }
    coerceAtMost(this_, value) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.CoerceAtMost(value)));
    }
    coerceAtLeast(this_, value) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.CoerceAtLeast(value)));
    }
    plusAssignNumber(this_, by) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.Increment(by)));
    }
    timesAssign(this_, by) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.Multiply(by)));
    }
    plusAssignString(this_, value) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.AppendString(value)));
    }
    plusAssignList(this_, items) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.ListAppend(items)));
    }
    plusAssignSet(this_, items) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.SetAppend(items)));
    }
    plusAssignItemList(this_, item) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.ListAppend([item])));
    }
    plusAssignItemSet(this_, item) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.SetAppend(new khrysalis_runtime_1.EqualOverrideSet([item]))));
    }
    plusAssignListAddAll(this_, items) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.ListAppend(items)));
    }
    plusAssignSetAddAll(this_, items) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.SetAppend(items)));
    }
    removeAllList(this_, condition) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.ListRemove((condition)((0, ConditionBuilder_1.path)()))));
    }
    removeAllSet(this_, condition) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.SetRemove((condition)((0, ConditionBuilder_1.path)()))));
    }
    removeAllItemsList(this_, items) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.ListRemoveInstances(items)));
    }
    removeAllItemsSet(this_, items) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.SetRemoveInstances(items)));
    }
    dropLastList(this_) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.ListDropLast()));
    }
    dropLastSet(this_) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.SetDropLast()));
    }
    dropFirstList(this_) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.ListDropFirst()));
    }
    dropFirstSet(this_) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.SetDropFirst()));
    }
    forEachList(this_, modification) {
        const builder = new ModificationBuilder();
        modification(builder, (0, ConditionBuilder_1.path)());
        this.modifications.push(this_.mapModification(new Modification_1.Modification.ListPerElement(new Condition_1.Condition.Always(), builder.build())));
    }
    forEachSet(this_, modification) {
        const builder = new ModificationBuilder();
        modification(builder, (0, ConditionBuilder_1.path)());
        this.modifications.push(this_.mapModification(new Modification_1.Modification.SetPerElement(new Condition_1.Condition.Always(), builder.build())));
    }
    forEachIfList(this_, condition, modification) {
        const builder = new ModificationBuilder();
        modification(builder, (0, ConditionBuilder_1.path)());
        this.modifications.push(this_.mapModification(new Modification_1.Modification.ListPerElement((condition)((0, ConditionBuilder_1.path)()), builder.build())));
    }
    forEachIfSet(this_, condition, modification) {
        const builder = new ModificationBuilder();
        modification(builder, (0, ConditionBuilder_1.path)());
        this.modifications.push(this_.mapModification(new Modification_1.Modification.SetPerElement((condition)((0, ConditionBuilder_1.path)()), builder.build())));
    }
    plusAssignMap(this_, map) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.Combine(map)));
    }
    modifyByKey(this_, byKey) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.ModifyByKey(new Map((0, iter_tools_es_1.map)(x => [x[0], ((it) => (modification(it[1])))(x)], byKey.entries())))));
    }
    removeKeys(this_, fields) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.RemoveKeys(fields)));
    }
}
exports.ModificationBuilder = ModificationBuilder;
//# sourceMappingURL=ModificationBuilder.js.map