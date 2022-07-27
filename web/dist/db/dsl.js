"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.xPropChainRemoveKeys = exports.xPropChainModifyByKey = exports.xPropChainPlusMap = exports.xPropChainMapIf = exports.xPropChainMap = exports.xPropChainDropFirst = exports.xPropChainDropLast = exports.xPropChainRemoveList = exports.xPropChainRemoveAll = exports.xPropChainAddUnique = exports.xPropChainAddAll = exports.xPropChainPlusItem = exports.xPropChainPlusItems = exports.xPropChainPlusString = exports.xPropChainTimes = exports.xPropChainPlusNumber = exports.xPropChainCoerceAtLeast = exports.xPropChainCoerceAtMost = exports.xPropChainAssign = exports.xPropChainModification = exports.xPropChainCondition = exports.xPropChainGet = exports.xPropChainNotNullGet = exports.xPropChainContainsKey = exports.xPropChainSizesEquals = exports.xPropChainAny = exports.xPropChainAll = exports.xPropChainFullTextSearch = exports.xPropChainContainsCased = exports.xPropChainContains = exports.xPropChainAnySet = exports.xPropChainAnyClear = exports.xPropChainAllSet = exports.xPropChainAllClear = exports.xPropChainLte = exports.xPropChainGte = exports.xPropChainLt = exports.xPropChainGt = exports.xPropChainNotIn = exports.xPropChainNin = exports.xPropChainInside = exports.xPropChainNe = exports.xPropChainNeq = exports.xPropChainEq = exports.xPropChainNeverGet = exports.xPropChainAlwaysGet = exports.modification = exports.condition = exports.PropChain = exports.startChain = void 0;
// Package: com.lightningkite.lightningdb
// Generated by Khrysalis - this file will be overwritten.
const Condition_1 = require("./Condition");
const Modification_1 = require("./Modification");
const iter_tools_es_1 = require("iter-tools-es");
//! Declares com.lightningkite.lightningdb.startChain
function startChain() {
    return new PropChain((it) => (it), (it) => (it));
}
exports.startChain = startChain;
//! Declares com.lightningkite.lightningdb.PropChain
class PropChain {
    constructor(mapCondition, mapModification) {
        this.mapCondition = mapCondition;
        this.mapModification = mapModification;
    }
    get(prop) {
        return new PropChain((it) => (this.mapCondition(new Condition_1.Condition.OnField(prop, it))), (it) => (this.mapModification(new Modification_1.Modification.OnField(prop, it))));
    }
    //    override fun hashCode(): Int = mapCondition(Condition.Always()).hashCode()
    toString() {
        return `PropChain(${this.mapCondition(new Condition_1.Condition.Always())})`;
    }
}
exports.PropChain = PropChain;
//! Declares com.lightningkite.lightningdb.condition
function condition(setup) {
    return (setup)(startChain());
}
exports.condition = condition;
//! Declares com.lightningkite.lightningdb.modification
function modification(setup) {
    return (setup)(startChain());
}
exports.modification = modification;
//! Declares com.lightningkite.lightningdb.always>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.always.K, com.lightningkite.lightningdb.always.K
function xPropChainAlwaysGet(this_) { return new Condition_1.Condition.Always(); }
exports.xPropChainAlwaysGet = xPropChainAlwaysGet;
//! Declares com.lightningkite.lightningdb.never>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.never.K, com.lightningkite.lightningdb.never.K
function xPropChainNeverGet(this_) { return new Condition_1.Condition.Never(); }
exports.xPropChainNeverGet = xPropChainNeverGet;
//! Declares com.lightningkite.lightningdb.eq>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.eq.K, com.lightningkite.lightningdb.eq.T
function xPropChainEq(this_, value) {
    return this_.mapCondition(new Condition_1.Condition.Equal(value));
}
exports.xPropChainEq = xPropChainEq;
//! Declares com.lightningkite.lightningdb.neq>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.neq.K, com.lightningkite.lightningdb.neq.T
function xPropChainNeq(this_, value) {
    return this_.mapCondition(new Condition_1.Condition.NotEqual(value));
}
exports.xPropChainNeq = xPropChainNeq;
//! Declares com.lightningkite.lightningdb.ne>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.ne.K, com.lightningkite.lightningdb.ne.T
function xPropChainNe(this_, value) {
    return this_.mapCondition(new Condition_1.Condition.NotEqual(value));
}
exports.xPropChainNe = xPropChainNe;
//! Declares com.lightningkite.lightningdb.inside>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.inside.K, com.lightningkite.lightningdb.inside.T
function xPropChainInside(this_, values) {
    return this_.mapCondition(new Condition_1.Condition.Inside(values));
}
exports.xPropChainInside = xPropChainInside;
//! Declares com.lightningkite.lightningdb.nin>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.nin.K, com.lightningkite.lightningdb.nin.T
function xPropChainNin(this_, values) {
    return this_.mapCondition(new Condition_1.Condition.NotInside(values));
}
exports.xPropChainNin = xPropChainNin;
//! Declares com.lightningkite.lightningdb.notIn>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.notIn.K, com.lightningkite.lightningdb.notIn.T
function xPropChainNotIn(this_, values) {
    return this_.mapCondition(new Condition_1.Condition.NotInside(values));
}
exports.xPropChainNotIn = xPropChainNotIn;
//! Declares com.lightningkite.lightningdb.gt>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.gt.K, com.lightningkite.lightningdb.gt.T
function xPropChainGt(this_, value) {
    return this_.mapCondition(new Condition_1.Condition.GreaterThan(value));
}
exports.xPropChainGt = xPropChainGt;
//! Declares com.lightningkite.lightningdb.lt>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.lt.K, com.lightningkite.lightningdb.lt.T
function xPropChainLt(this_, value) {
    return this_.mapCondition(new Condition_1.Condition.LessThan(value));
}
exports.xPropChainLt = xPropChainLt;
//! Declares com.lightningkite.lightningdb.gte>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.gte.K, com.lightningkite.lightningdb.gte.T
function xPropChainGte(this_, value) {
    return this_.mapCondition(new Condition_1.Condition.GreaterThanOrEqual(value));
}
exports.xPropChainGte = xPropChainGte;
//! Declares com.lightningkite.lightningdb.lte>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.lte.K, com.lightningkite.lightningdb.lte.T
function xPropChainLte(this_, value) {
    return this_.mapCondition(new Condition_1.Condition.LessThanOrEqual(value));
}
exports.xPropChainLte = xPropChainLte;
//! Declares com.lightningkite.lightningdb.allClear>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.allClear.K, kotlin.Int
function xPropChainAllClear(this_, mask) {
    return this_.mapCondition(new Condition_1.Condition.IntBitsClear(mask));
}
exports.xPropChainAllClear = xPropChainAllClear;
//! Declares com.lightningkite.lightningdb.allSet>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.allSet.K, kotlin.Int
function xPropChainAllSet(this_, mask) {
    return this_.mapCondition(new Condition_1.Condition.IntBitsSet(mask));
}
exports.xPropChainAllSet = xPropChainAllSet;
//! Declares com.lightningkite.lightningdb.anyClear>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.anyClear.K, kotlin.Int
function xPropChainAnyClear(this_, mask) {
    return this_.mapCondition(new Condition_1.Condition.IntBitsAnyClear(mask));
}
exports.xPropChainAnyClear = xPropChainAnyClear;
//! Declares com.lightningkite.lightningdb.anySet>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.anySet.K, kotlin.Int
function xPropChainAnySet(this_, mask) {
    return this_.mapCondition(new Condition_1.Condition.IntBitsAnySet(mask));
}
exports.xPropChainAnySet = xPropChainAnySet;
//! Declares com.lightningkite.lightningdb.contains>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.contains.K, kotlin.String
function xPropChainContains(this_, value) {
    return this_.mapCondition(new Condition_1.Condition.StringContains(value, true));
}
exports.xPropChainContains = xPropChainContains;
//! Declares com.lightningkite.lightningdb.contains>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.contains.K, kotlin.String
function xPropChainContainsCased(this_, value, ignoreCase) {
    return this_.mapCondition(new Condition_1.Condition.StringContains(value, ignoreCase));
}
exports.xPropChainContainsCased = xPropChainContainsCased;
//! Declares com.lightningkite.lightningdb.fullTextSearch>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.fullTextSearch.K, com.lightningkite.lightningdb.fullTextSearch.V
function xPropChainFullTextSearch(this_, value, ignoreCase) {
    return this_.mapCondition(new Condition_1.Condition.FullTextSearch(value, ignoreCase));
}
exports.xPropChainFullTextSearch = xPropChainFullTextSearch;
//! Declares com.lightningkite.lightningdb.all>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.all.K, kotlin.collections.Listcom.lightningkite.lightningdb.all.T
function xPropChainAll(this_, condition) {
    return this_.mapCondition(new Condition_1.Condition.AllElements((condition)(startChain())));
}
exports.xPropChainAll = xPropChainAll;
//! Declares com.lightningkite.lightningdb.any>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.any.K, kotlin.collections.Listcom.lightningkite.lightningdb.any.T
function xPropChainAny(this_, condition) {
    return this_.mapCondition(new Condition_1.Condition.AnyElements((condition)(startChain())));
}
exports.xPropChainAny = xPropChainAny;
//! Declares com.lightningkite.lightningdb.sizesEquals>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.sizesEquals.K, kotlin.collections.Listcom.lightningkite.lightningdb.sizesEquals.T
function xPropChainSizesEquals(this_, count) {
    return this_.mapCondition(new Condition_1.Condition.SizesEquals(count));
}
exports.xPropChainSizesEquals = xPropChainSizesEquals;
//! Declares com.lightningkite.lightningdb.containsKey>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.containsKey.K, kotlin.collections.Mapkotlin.String, com.lightningkite.lightningdb.containsKey.T
function xPropChainContainsKey(this_, key) {
    return this_.mapCondition(new Condition_1.Condition.Exists(key));
}
exports.xPropChainContainsKey = xPropChainContainsKey;
//! Declares com.lightningkite.lightningdb.notNull>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.notNull.K, com.lightningkite.lightningdb.notNull.T
function xPropChainNotNullGet(this_) { return new PropChain((it) => (this_.mapCondition(new Condition_1.Condition.IfNotNull(it))), (it) => (this_.mapModification(new Modification_1.Modification.IfNotNull(it)))); }
exports.xPropChainNotNullGet = xPropChainNotNullGet;
//! Declares com.lightningkite.lightningdb.get>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.get.K, kotlin.collections.Mapkotlin.String, com.lightningkite.lightningdb.get.T
function xPropChainGet(this_, key) {
    return new PropChain((it) => (this_.mapCondition(new Condition_1.Condition.OnKey(key, it))), (it) => (this_.mapModification(new Modification_1.Modification.ModifyByKey(new Map([[key, it]])))));
}
exports.xPropChainGet = xPropChainGet;
//! Declares com.lightningkite.lightningdb.condition>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.condition.K, com.lightningkite.lightningdb.condition.T
function xPropChainCondition(this_, make) {
    return this_.mapCondition(make(startChain()));
}
exports.xPropChainCondition = xPropChainCondition;
//! Declares com.lightningkite.lightningdb.modification>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.modification.K, com.lightningkite.lightningdb.modification.T
function xPropChainModification(this_, make) {
    return this_.mapModification(make(startChain()));
}
exports.xPropChainModification = xPropChainModification;
//! Declares com.lightningkite.lightningdb.assign>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.assign.K, com.lightningkite.lightningdb.assign.T
function xPropChainAssign(this_, value) {
    return this_.mapModification(new Modification_1.Modification.Assign(value));
}
exports.xPropChainAssign = xPropChainAssign;
//! Declares com.lightningkite.lightningdb.coerceAtMost>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.coerceAtMost.K, com.lightningkite.lightningdb.coerceAtMost.T
function xPropChainCoerceAtMost(this_, value) {
    return this_.mapModification(new Modification_1.Modification.CoerceAtMost(value));
}
exports.xPropChainCoerceAtMost = xPropChainCoerceAtMost;
//! Declares com.lightningkite.lightningdb.coerceAtLeast>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.coerceAtLeast.K, com.lightningkite.lightningdb.coerceAtLeast.T
function xPropChainCoerceAtLeast(this_, value) {
    return this_.mapModification(new Modification_1.Modification.CoerceAtLeast(value));
}
exports.xPropChainCoerceAtLeast = xPropChainCoerceAtLeast;
//! Declares com.lightningkite.lightningdb.plus>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.plus.K, com.lightningkite.lightningdb.plus.T
function xPropChainPlusNumber(this_, by) {
    return this_.mapModification(new Modification_1.Modification.Increment(by));
}
exports.xPropChainPlusNumber = xPropChainPlusNumber;
//! Declares com.lightningkite.lightningdb.times>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.times.K, com.lightningkite.lightningdb.times.T
function xPropChainTimes(this_, by) {
    return this_.mapModification(new Modification_1.Modification.Multiply(by));
}
exports.xPropChainTimes = xPropChainTimes;
//! Declares com.lightningkite.lightningdb.plus>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.plus.K, kotlin.String
function xPropChainPlusString(this_, value) {
    return this_.mapModification(new Modification_1.Modification.AppendString(value));
}
exports.xPropChainPlusString = xPropChainPlusString;
//! Declares com.lightningkite.lightningdb.plus>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.plus.K, kotlin.collections.Listcom.lightningkite.lightningdb.plus.T
function xPropChainPlusItems(this_, items) {
    return this_.mapModification(new Modification_1.Modification.AppendList(items));
}
exports.xPropChainPlusItems = xPropChainPlusItems;
//! Declares com.lightningkite.lightningdb.plus>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.plus.K, kotlin.collections.Listcom.lightningkite.lightningdb.plus.T
function xPropChainPlusItem(this_, item) {
    return this_.mapModification(new Modification_1.Modification.AppendList([item]));
}
exports.xPropChainPlusItem = xPropChainPlusItem;
//! Declares com.lightningkite.lightningdb.addAll>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.addAll.K, kotlin.collections.Listcom.lightningkite.lightningdb.addAll.T
function xPropChainAddAll(this_, items) {
    return this_.mapModification(new Modification_1.Modification.AppendList(items));
}
exports.xPropChainAddAll = xPropChainAddAll;
//! Declares com.lightningkite.lightningdb.addUnique>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.addUnique.K, kotlin.collections.Listcom.lightningkite.lightningdb.addUnique.T
function xPropChainAddUnique(this_, items) {
    return this_.mapModification(new Modification_1.Modification.AppendSet(items));
}
exports.xPropChainAddUnique = xPropChainAddUnique;
//! Declares com.lightningkite.lightningdb.removeAll>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.removeAll.K, kotlin.collections.Listcom.lightningkite.lightningdb.removeAll.T
function xPropChainRemoveAll(this_, condition) {
    return this_.mapModification(new Modification_1.Modification.Remove((condition)(startChain())));
}
exports.xPropChainRemoveAll = xPropChainRemoveAll;
//! Declares com.lightningkite.lightningdb.removeAll>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.removeAll.K, kotlin.collections.Listcom.lightningkite.lightningdb.removeAll.T
function xPropChainRemoveList(this_, items) {
    return this_.mapModification(new Modification_1.Modification.RemoveInstances(items));
}
exports.xPropChainRemoveList = xPropChainRemoveList;
//! Declares com.lightningkite.lightningdb.dropLast>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.dropLast.K, kotlin.collections.Listcom.lightningkite.lightningdb.dropLast.T
function xPropChainDropLast(this_) {
    return this_.mapModification(new Modification_1.Modification.DropLast());
}
exports.xPropChainDropLast = xPropChainDropLast;
//! Declares com.lightningkite.lightningdb.dropFirst>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.dropFirst.K, kotlin.collections.Listcom.lightningkite.lightningdb.dropFirst.T
function xPropChainDropFirst(this_) {
    return this_.mapModification(new Modification_1.Modification.DropFirst());
}
exports.xPropChainDropFirst = xPropChainDropFirst;
//! Declares com.lightningkite.lightningdb.map>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.map.K, kotlin.collections.Listcom.lightningkite.lightningdb.map.T
function xPropChainMap(this_, modification) {
    return this_.mapModification(new Modification_1.Modification.PerElement(new Condition_1.Condition.Always(), (modification)(startChain())));
}
exports.xPropChainMap = xPropChainMap;
//! Declares com.lightningkite.lightningdb.mapIf>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.mapIf.K, kotlin.collections.Listcom.lightningkite.lightningdb.mapIf.T
function xPropChainMapIf(this_, condition, modification) {
    return this_.mapModification(new Modification_1.Modification.PerElement((condition)(startChain()), (modification)(startChain())));
}
exports.xPropChainMapIf = xPropChainMapIf;
//! Declares com.lightningkite.lightningdb.plus>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.plus.K, kotlin.collections.Mapkotlin.String, com.lightningkite.lightningdb.plus.T
function xPropChainPlusMap(this_, map) {
    return this_.mapModification(new Modification_1.Modification.Combine(map));
}
exports.xPropChainPlusMap = xPropChainPlusMap;
//! Declares com.lightningkite.lightningdb.modifyByKey>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.modifyByKey.K, kotlin.collections.Mapkotlin.String, com.lightningkite.lightningdb.modifyByKey.T
function xPropChainModifyByKey(this_, map) {
    return this_.mapModification(new Modification_1.Modification.ModifyByKey(new Map((0, iter_tools_es_1.map)(x => [x[0], ((it) => ((it[1])(startChain())))(x)], map.entries()))));
}
exports.xPropChainModifyByKey = xPropChainModifyByKey;
//! Declares com.lightningkite.lightningdb.removeKeys>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.removeKeys.K, kotlin.collections.Mapkotlin.String, com.lightningkite.lightningdb.removeKeys.T
function xPropChainRemoveKeys(this_, fields) {
    return this_.mapModification(new Modification_1.Modification.RemoveKeys(fields));
}
exports.xPropChainRemoveKeys = xPropChainRemoveKeys;
//# sourceMappingURL=dsl.js.map