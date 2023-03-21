"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ModificationBuilder = exports.modification = void 0;
// Package: com.lightningkite.lightningdb
// Generated by Khrysalis - this file will be overwritten.
const Condition_1 = require("./Condition");
const Modification_1 = require("./Modification");
const dsl_1 = require("./dsl");
const khrysalis_runtime_1 = require("@lightningkite/khrysalis-runtime");
const iter_tools_es_1 = require("iter-tools-es");
//! Declares com.lightningkite.lightningdb.modification
function modification(setup) {
    return (0, khrysalis_runtime_1.also)(new ModificationBuilder(), (this_) => {
        setup(this_, (0, dsl_1.startChain)());
    }).build();
}
exports.modification = modification;
//! Declares com.lightningkite.lightningdb.ModificationBuilder
class ModificationBuilder {
    constructor() {
        this.modifications = [];
    }
    build() {
        var _a;
        return (_a = (() => {
            const temp2 = this.modifications;
            return (temp2.length == 1 ? temp2[0] : null);
        })()) !== null && _a !== void 0 ? _a : new Modification_1.Modification.Chain(this.modifications);
    }
    xPropChainAssign(this_, value) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.Assign(value)));
    }
    xPropChainCoerceAtMost(this_, value) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.CoerceAtMost(value)));
    }
    xPropChainCoerceAtLeast(this_, value) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.CoerceAtLeast(value)));
    }
    xPropChainPlusNumberOld(this_, by) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.Increment(by)));
    }
    xPropChainTimes(this_, by) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.Multiply(by)));
    }
    xPropChainPlusStringOld(this_, value) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.AppendString(value)));
    }
    xPropChainPlusItemsListOld(this_, items) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.ListAppend(items)));
    }
    xPropChainPlusItemsSetOld(this_, items) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.SetAppend(items)));
    }
    xPropChainPlusItemListOld(this_, item) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.ListAppend([item])));
    }
    xPropChainPlusItemSetOld(this_, item) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.SetAppend(new khrysalis_runtime_1.EqualOverrideSet([item]))));
    }
    xPropChainPlusNumber(this_, by) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.Increment(by)));
    }
    xPropChainTimesAssign(this_, by) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.Multiply(by)));
    }
    xPropChainPlusString(this_, value) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.AppendString(value)));
    }
    xPropChainPlusItemsList(this_, items) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.ListAppend(items)));
    }
    xPropChainPlusItemsSet(this_, items) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.SetAppend(items)));
    }
    xPropChainPlusItemList(this_, item) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.ListAppend([item])));
    }
    xPropChainPlusItemSet(this_, item) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.SetAppend(new khrysalis_runtime_1.EqualOverrideSet([item]))));
    }
    xPropChainListAddAll(this_, items) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.ListAppend(items)));
    }
    xPropChainSetAddAll(this_, items) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.SetAppend(items)));
    }
    xPropChainListRemove(this_, condition) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.ListRemove((condition)((0, dsl_1.startChain)()))));
    }
    xPropChainSetRemove(this_, condition) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.SetRemove((condition)((0, dsl_1.startChain)()))));
    }
    xPropChainListRemoveAll(this_, items) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.ListRemoveInstances(items)));
    }
    xPropChainSetRemoveAll(this_, items) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.SetRemoveInstances(items)));
    }
    xPropChainListDropLast(this_) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.ListDropLast()));
    }
    xPropChainSetDropLast(this_) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.SetDropLast()));
    }
    xPropChainListDropFirst(this_) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.ListDropFirst()));
    }
    xPropChainSetDropFirst(this_) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.SetDropFirst()));
    }
    xPropChainListMap(this_, modification) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.ListPerElement(new Condition_1.Condition.Always(), (0, khrysalis_runtime_1.also)(new ModificationBuilder(), (this_1) => {
            modification(this_1, (0, dsl_1.startChain)());
        }).build())));
    }
    xPropChainSetMap(this_, modification) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.SetPerElement(new Condition_1.Condition.Always(), (0, khrysalis_runtime_1.also)(new ModificationBuilder(), (this_1) => {
            modification(this_1, (0, dsl_1.startChain)());
        }).build())));
    }
    xPropChainListMapIf(this_, condition, modification) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.ListPerElement((condition)((0, dsl_1.startChain)()), (0, khrysalis_runtime_1.also)(new ModificationBuilder(), (this_1) => {
            modification(this_1, (0, dsl_1.startChain)());
        }).build())));
    }
    xPropChainSetMapIf(this_, condition, modification) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.SetPerElement((condition)((0, dsl_1.startChain)()), (0, khrysalis_runtime_1.also)(new ModificationBuilder(), (this_1) => {
            modification(this_1, (0, dsl_1.startChain)());
        }).build())));
    }
    xPropChainPlusMap(this_, map) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.Combine(map)));
    }
    xPropChainModifyByKey(this_, modifications) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.ModifyByKey(new Map((0, iter_tools_es_1.map)(x => [x[0], ((it) => (modification(it[1])))(x)], modifications.entries())))));
    }
    xPropChainRemoveKeys(this_, fields) {
        this.modifications.push(this_.mapModification(new Modification_1.Modification.RemoveKeys(fields)));
    }
}
exports.ModificationBuilder = ModificationBuilder;
//# sourceMappingURL=ModificationBuilder.js.map