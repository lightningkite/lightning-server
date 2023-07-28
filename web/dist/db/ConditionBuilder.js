"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.xCMBuilderAllClear = exports.xCMBuilderLte = exports.xCMBuilderGte = exports.xCMBuilderLt = exports.xCMBuilderGt = exports.xCMBuilderNotIn = exports.xCMBuilderNotInSet = exports.xCMBuilderNin = exports.xCMBuilderNinSet = exports.xCMBuilderInside = exports.xCMBuilderInsideSet = exports.xCMBuilderNe = exports.xCMBuilderNeq = exports.xCMBuilderEq = exports.xCMBuilderNeverGet = exports.xCMBuilderAlwaysGet = exports.xDataClassPathCondition = exports.xDataClassPathContainsKey = exports.xDataClassPathSetSizedEqual = exports.xDataClassPathSetAny = exports.xDataClassPathSetAll = exports.xDataClassPathListSizedEqual = exports.xDataClassPathListAny = exports.xDataClassPathListAll = exports.xDataClassPathFullTextSearch = exports.xDataClassPathContainsCased = exports.xDataClassPathContains = exports.xDataClassPathAnySet = exports.xDataClassPathAnyClear = exports.xDataClassPathAllSet = exports.xDataClassPathAllClear = exports.xDataClassPathLte = exports.xDataClassPathGte = exports.xDataClassPathLt = exports.xDataClassPathGt = exports.xDataClassPathNotIn = exports.xDataClassPathNotInSet = exports.xDataClassPathNin = exports.xDataClassPathNinSet = exports.xDataClassPathInside = exports.xDataClassPathInsideSet = exports.xDataClassPathNe = exports.xDataClassPathNeq = exports.xDataClassPathEq = exports.xDataClassPathNeverGet = exports.xDataClassPathAlwaysGet = exports.condition = exports.path = exports.xDataClassPathToCMBuilder = exports.CMBuilder = void 0;
exports.xUnitThen = exports.xCMBuilderSetAnyGet = exports.xCMBuilderListAnyGet = exports.xCMBuilderSetAllGet = exports.xCMBuilderListAllGet = exports.xCMBuilderGet = exports.xCMBuilderNotNullGet = exports.xCMBuilderCondition = exports.xCMBuilderContainsKey = exports.xCMBuilderSetSizedEqual = exports.xCMBuilderSetAny = exports.xCMBuilderSetAll = exports.xCMBuilderListSizedEqual = exports.xCMBuilderListAny = exports.xCMBuilderListAll = exports.xCMBuilderFullTextSearch = exports.xCMBuilderContainsCased = exports.xCMBuilderContains = exports.xCMBuilderAnySet = exports.xCMBuilderAnyClear = exports.xCMBuilderAllSet = void 0;
// Package: com.lightningkite.lightningdb
// Generated by Khrysalis - this file will be overwritten.
const Condition_1 = require("./Condition");
const DataClassPath_1 = require("./DataClassPath");
const Modification_1 = require("./Modification");
const iter_tools_es_1 = require("iter-tools-es");
//! Declares com.lightningkite.lightningdb.CMBuilder
class CMBuilder {
    constructor(mapCondition, mapModification) {
        this.mapCondition = mapCondition;
        this.mapModification = mapModification;
    }
}
exports.CMBuilder = CMBuilder;
//! Declares com.lightningkite.lightningdb.toCMBuilder>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.toCMBuilder.From, com.lightningkite.lightningdb.toCMBuilder.To
function xDataClassPathToCMBuilder(this_) {
    return new CMBuilder((it) => (this_.mapCondition(it)), (it) => (this_.mapModification(it)));
}
exports.xDataClassPathToCMBuilder = xDataClassPathToCMBuilder;
//! Declares com.lightningkite.lightningdb.path
function path() {
    return new DataClassPath_1.DataClassPathSelf();
}
exports.path = path;
//! Declares com.lightningkite.lightningdb.condition
function condition(setup) {
    return (setup)(path());
}
exports.condition = condition;
//! Declares com.lightningkite.lightningdb.always>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.always.K, com.lightningkite.lightningdb.always.K
function xDataClassPathAlwaysGet(this_) { return new Condition_1.Condition.Always(); }
exports.xDataClassPathAlwaysGet = xDataClassPathAlwaysGet;
//! Declares com.lightningkite.lightningdb.never>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.never.K, com.lightningkite.lightningdb.never.K
function xDataClassPathNeverGet(this_) { return new Condition_1.Condition.Never(); }
exports.xDataClassPathNeverGet = xDataClassPathNeverGet;
//! Declares com.lightningkite.lightningdb.eq>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.eq.K, com.lightningkite.lightningdb.eq.T
function xDataClassPathEq(this_, value) {
    return this_.mapCondition(new Condition_1.Condition.Equal(value));
}
exports.xDataClassPathEq = xDataClassPathEq;
//! Declares com.lightningkite.lightningdb.neq>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.neq.K, com.lightningkite.lightningdb.neq.T
function xDataClassPathNeq(this_, value) {
    return this_.mapCondition(new Condition_1.Condition.NotEqual(value));
}
exports.xDataClassPathNeq = xDataClassPathNeq;
//! Declares com.lightningkite.lightningdb.ne>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.ne.K, com.lightningkite.lightningdb.ne.T
function xDataClassPathNe(this_, value) {
    return this_.mapCondition(new Condition_1.Condition.NotEqual(value));
}
exports.xDataClassPathNe = xDataClassPathNe;
//! Declares com.lightningkite.lightningdb.inside>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.inside.K, com.lightningkite.lightningdb.inside.T
function xDataClassPathInsideSet(this_, values) {
    return this_.mapCondition(new Condition_1.Condition.Inside((0, iter_tools_es_1.toArray)(values)));
}
exports.xDataClassPathInsideSet = xDataClassPathInsideSet;
//! Declares com.lightningkite.lightningdb.inside>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.inside.K, com.lightningkite.lightningdb.inside.T
function xDataClassPathInside(this_, values) {
    return this_.mapCondition(new Condition_1.Condition.Inside(values));
}
exports.xDataClassPathInside = xDataClassPathInside;
//! Declares com.lightningkite.lightningdb.nin>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.nin.K, com.lightningkite.lightningdb.nin.T
function xDataClassPathNinSet(this_, values) {
    return this_.mapCondition(new Condition_1.Condition.NotInside((0, iter_tools_es_1.toArray)(values)));
}
exports.xDataClassPathNinSet = xDataClassPathNinSet;
//! Declares com.lightningkite.lightningdb.nin>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.nin.K, com.lightningkite.lightningdb.nin.T
function xDataClassPathNin(this_, values) {
    return this_.mapCondition(new Condition_1.Condition.NotInside(values));
}
exports.xDataClassPathNin = xDataClassPathNin;
//! Declares com.lightningkite.lightningdb.notIn>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.notIn.K, com.lightningkite.lightningdb.notIn.T
function xDataClassPathNotInSet(this_, values) {
    return this_.mapCondition(new Condition_1.Condition.NotInside((0, iter_tools_es_1.toArray)(values)));
}
exports.xDataClassPathNotInSet = xDataClassPathNotInSet;
//! Declares com.lightningkite.lightningdb.notIn>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.notIn.K, com.lightningkite.lightningdb.notIn.T
function xDataClassPathNotIn(this_, values) {
    return this_.mapCondition(new Condition_1.Condition.NotInside(values));
}
exports.xDataClassPathNotIn = xDataClassPathNotIn;
//! Declares com.lightningkite.lightningdb.gt>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.gt.K, com.lightningkite.lightningdb.gt.T
function xDataClassPathGt(this_, value) {
    return this_.mapCondition(new Condition_1.Condition.GreaterThan(value));
}
exports.xDataClassPathGt = xDataClassPathGt;
//! Declares com.lightningkite.lightningdb.lt>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.lt.K, com.lightningkite.lightningdb.lt.T
function xDataClassPathLt(this_, value) {
    return this_.mapCondition(new Condition_1.Condition.LessThan(value));
}
exports.xDataClassPathLt = xDataClassPathLt;
//! Declares com.lightningkite.lightningdb.gte>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.gte.K, com.lightningkite.lightningdb.gte.T
function xDataClassPathGte(this_, value) {
    return this_.mapCondition(new Condition_1.Condition.GreaterThanOrEqual(value));
}
exports.xDataClassPathGte = xDataClassPathGte;
//! Declares com.lightningkite.lightningdb.lte>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.lte.K, com.lightningkite.lightningdb.lte.T
function xDataClassPathLte(this_, value) {
    return this_.mapCondition(new Condition_1.Condition.LessThanOrEqual(value));
}
exports.xDataClassPathLte = xDataClassPathLte;
//! Declares com.lightningkite.lightningdb.allClear>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.allClear.K, kotlin.Int
function xDataClassPathAllClear(this_, mask) {
    return this_.mapCondition(new Condition_1.Condition.IntBitsClear(mask));
}
exports.xDataClassPathAllClear = xDataClassPathAllClear;
//! Declares com.lightningkite.lightningdb.allSet>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.allSet.K, kotlin.Int
function xDataClassPathAllSet(this_, mask) {
    return this_.mapCondition(new Condition_1.Condition.IntBitsSet(mask));
}
exports.xDataClassPathAllSet = xDataClassPathAllSet;
//! Declares com.lightningkite.lightningdb.anyClear>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.anyClear.K, kotlin.Int
function xDataClassPathAnyClear(this_, mask) {
    return this_.mapCondition(new Condition_1.Condition.IntBitsAnyClear(mask));
}
exports.xDataClassPathAnyClear = xDataClassPathAnyClear;
//! Declares com.lightningkite.lightningdb.anySet>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.anySet.K, kotlin.Int
function xDataClassPathAnySet(this_, mask) {
    return this_.mapCondition(new Condition_1.Condition.IntBitsAnySet(mask));
}
exports.xDataClassPathAnySet = xDataClassPathAnySet;
//! Declares com.lightningkite.lightningdb.contains>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.contains.K, kotlin.String
function xDataClassPathContains(this_, value) {
    return this_.mapCondition(new Condition_1.Condition.StringContains(value, true));
}
exports.xDataClassPathContains = xDataClassPathContains;
//! Declares com.lightningkite.lightningdb.contains>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.contains.K, kotlin.String
function xDataClassPathContainsCased(this_, value, ignoreCase) {
    return this_.mapCondition(new Condition_1.Condition.StringContains(value, ignoreCase));
}
exports.xDataClassPathContainsCased = xDataClassPathContainsCased;
//! Declares com.lightningkite.lightningdb.fullTextSearch>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.fullTextSearch.K, com.lightningkite.lightningdb.fullTextSearch.V
function xDataClassPathFullTextSearch(this_, value, ignoreCase) {
    return this_.mapCondition(new Condition_1.Condition.FullTextSearch(value, ignoreCase));
}
exports.xDataClassPathFullTextSearch = xDataClassPathFullTextSearch;
//! Declares com.lightningkite.lightningdb.all>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.all.K, kotlin.collections.Listcom.lightningkite.lightningdb.all.T
function xDataClassPathListAll(this_, condition) {
    return this_.mapCondition(new Condition_1.Condition.ListAllElements((condition)(path())));
}
exports.xDataClassPathListAll = xDataClassPathListAll;
//! Declares com.lightningkite.lightningdb.any>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.any.K, kotlin.collections.Listcom.lightningkite.lightningdb.any.T
function xDataClassPathListAny(this_, condition) {
    return this_.mapCondition(new Condition_1.Condition.ListAnyElements((condition)(path())));
}
exports.xDataClassPathListAny = xDataClassPathListAny;
//! Declares com.lightningkite.lightningdb.sizesEquals>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.sizesEquals.K, kotlin.collections.Listcom.lightningkite.lightningdb.sizesEquals.T
function xDataClassPathListSizedEqual(this_, count) {
    return this_.mapCondition(new Condition_1.Condition.ListSizesEquals(count));
}
exports.xDataClassPathListSizedEqual = xDataClassPathListSizedEqual;
//! Declares com.lightningkite.lightningdb.all>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.all.K, kotlin.collections.Setcom.lightningkite.lightningdb.all.T
function xDataClassPathSetAll(this_, condition) {
    return this_.mapCondition(new Condition_1.Condition.SetAllElements((condition)(path())));
}
exports.xDataClassPathSetAll = xDataClassPathSetAll;
//! Declares com.lightningkite.lightningdb.any>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.any.K, kotlin.collections.Setcom.lightningkite.lightningdb.any.T
function xDataClassPathSetAny(this_, condition) {
    return this_.mapCondition(new Condition_1.Condition.SetAnyElements((condition)(path())));
}
exports.xDataClassPathSetAny = xDataClassPathSetAny;
//! Declares com.lightningkite.lightningdb.sizesEquals>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.sizesEquals.K, kotlin.collections.Setcom.lightningkite.lightningdb.sizesEquals.T
function xDataClassPathSetSizedEqual(this_, count) {
    return this_.mapCondition(new Condition_1.Condition.SetSizesEquals(count));
}
exports.xDataClassPathSetSizedEqual = xDataClassPathSetSizedEqual;
//! Declares com.lightningkite.lightningdb.containsKey>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.containsKey.K, kotlin.collections.Mapkotlin.String, com.lightningkite.lightningdb.containsKey.T
function xDataClassPathContainsKey(this_, key) {
    return this_.mapCondition(new Condition_1.Condition.Exists(key));
}
exports.xDataClassPathContainsKey = xDataClassPathContainsKey;
//! Declares com.lightningkite.lightningdb.condition>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.condition.K, com.lightningkite.lightningdb.condition.T
function xDataClassPathCondition(this_, make) {
    return this_.mapCondition(make(path()));
}
exports.xDataClassPathCondition = xDataClassPathCondition;
//////////////
//! Declares com.lightningkite.lightningdb.always>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.always.K, com.lightningkite.lightningdb.always.K
function xCMBuilderAlwaysGet(this_) { return new Condition_1.Condition.Always(); }
exports.xCMBuilderAlwaysGet = xCMBuilderAlwaysGet;
//! Declares com.lightningkite.lightningdb.never>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.never.K, com.lightningkite.lightningdb.never.K
function xCMBuilderNeverGet(this_) { return new Condition_1.Condition.Never(); }
exports.xCMBuilderNeverGet = xCMBuilderNeverGet;
//! Declares com.lightningkite.lightningdb.eq>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.eq.K, com.lightningkite.lightningdb.eq.T
function xCMBuilderEq(this_, value) {
    return this_.mapCondition(new Condition_1.Condition.Equal(value));
}
exports.xCMBuilderEq = xCMBuilderEq;
//! Declares com.lightningkite.lightningdb.neq>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.neq.K, com.lightningkite.lightningdb.neq.T
function xCMBuilderNeq(this_, value) {
    return this_.mapCondition(new Condition_1.Condition.NotEqual(value));
}
exports.xCMBuilderNeq = xCMBuilderNeq;
//! Declares com.lightningkite.lightningdb.ne>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.ne.K, com.lightningkite.lightningdb.ne.T
function xCMBuilderNe(this_, value) {
    return this_.mapCondition(new Condition_1.Condition.NotEqual(value));
}
exports.xCMBuilderNe = xCMBuilderNe;
//! Declares com.lightningkite.lightningdb.inside>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.inside.K, com.lightningkite.lightningdb.inside.T
function xCMBuilderInsideSet(this_, values) {
    return this_.mapCondition(new Condition_1.Condition.Inside((0, iter_tools_es_1.toArray)(values)));
}
exports.xCMBuilderInsideSet = xCMBuilderInsideSet;
//! Declares com.lightningkite.lightningdb.inside>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.inside.K, com.lightningkite.lightningdb.inside.T
function xCMBuilderInside(this_, values) {
    return this_.mapCondition(new Condition_1.Condition.Inside(values));
}
exports.xCMBuilderInside = xCMBuilderInside;
//! Declares com.lightningkite.lightningdb.nin>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.nin.K, com.lightningkite.lightningdb.nin.T
function xCMBuilderNinSet(this_, values) {
    return this_.mapCondition(new Condition_1.Condition.NotInside((0, iter_tools_es_1.toArray)(values)));
}
exports.xCMBuilderNinSet = xCMBuilderNinSet;
//! Declares com.lightningkite.lightningdb.nin>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.nin.K, com.lightningkite.lightningdb.nin.T
function xCMBuilderNin(this_, values) {
    return this_.mapCondition(new Condition_1.Condition.NotInside(values));
}
exports.xCMBuilderNin = xCMBuilderNin;
//! Declares com.lightningkite.lightningdb.notIn>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.notIn.K, com.lightningkite.lightningdb.notIn.T
function xCMBuilderNotInSet(this_, values) {
    return this_.mapCondition(new Condition_1.Condition.NotInside((0, iter_tools_es_1.toArray)(values)));
}
exports.xCMBuilderNotInSet = xCMBuilderNotInSet;
//! Declares com.lightningkite.lightningdb.notIn>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.notIn.K, com.lightningkite.lightningdb.notIn.T
function xCMBuilderNotIn(this_, values) {
    return this_.mapCondition(new Condition_1.Condition.NotInside(values));
}
exports.xCMBuilderNotIn = xCMBuilderNotIn;
//! Declares com.lightningkite.lightningdb.gt>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.gt.K, com.lightningkite.lightningdb.gt.T
function xCMBuilderGt(this_, value) {
    return this_.mapCondition(new Condition_1.Condition.GreaterThan(value));
}
exports.xCMBuilderGt = xCMBuilderGt;
//! Declares com.lightningkite.lightningdb.lt>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.lt.K, com.lightningkite.lightningdb.lt.T
function xCMBuilderLt(this_, value) {
    return this_.mapCondition(new Condition_1.Condition.LessThan(value));
}
exports.xCMBuilderLt = xCMBuilderLt;
//! Declares com.lightningkite.lightningdb.gte>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.gte.K, com.lightningkite.lightningdb.gte.T
function xCMBuilderGte(this_, value) {
    return this_.mapCondition(new Condition_1.Condition.GreaterThanOrEqual(value));
}
exports.xCMBuilderGte = xCMBuilderGte;
//! Declares com.lightningkite.lightningdb.lte>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.lte.K, com.lightningkite.lightningdb.lte.T
function xCMBuilderLte(this_, value) {
    return this_.mapCondition(new Condition_1.Condition.LessThanOrEqual(value));
}
exports.xCMBuilderLte = xCMBuilderLte;
//! Declares com.lightningkite.lightningdb.allClear>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.allClear.K, kotlin.Int
function xCMBuilderAllClear(this_, mask) {
    return this_.mapCondition(new Condition_1.Condition.IntBitsClear(mask));
}
exports.xCMBuilderAllClear = xCMBuilderAllClear;
//! Declares com.lightningkite.lightningdb.allSet>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.allSet.K, kotlin.Int
function xCMBuilderAllSet(this_, mask) {
    return this_.mapCondition(new Condition_1.Condition.IntBitsSet(mask));
}
exports.xCMBuilderAllSet = xCMBuilderAllSet;
//! Declares com.lightningkite.lightningdb.anyClear>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.anyClear.K, kotlin.Int
function xCMBuilderAnyClear(this_, mask) {
    return this_.mapCondition(new Condition_1.Condition.IntBitsAnyClear(mask));
}
exports.xCMBuilderAnyClear = xCMBuilderAnyClear;
//! Declares com.lightningkite.lightningdb.anySet>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.anySet.K, kotlin.Int
function xCMBuilderAnySet(this_, mask) {
    return this_.mapCondition(new Condition_1.Condition.IntBitsAnySet(mask));
}
exports.xCMBuilderAnySet = xCMBuilderAnySet;
//! Declares com.lightningkite.lightningdb.contains>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.contains.K, kotlin.String
function xCMBuilderContains(this_, value) {
    return this_.mapCondition(new Condition_1.Condition.StringContains(value, true));
}
exports.xCMBuilderContains = xCMBuilderContains;
//! Declares com.lightningkite.lightningdb.contains>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.contains.K, kotlin.String
function xCMBuilderContainsCased(this_, value, ignoreCase) {
    return this_.mapCondition(new Condition_1.Condition.StringContains(value, ignoreCase));
}
exports.xCMBuilderContainsCased = xCMBuilderContainsCased;
//! Declares com.lightningkite.lightningdb.fullTextSearch>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.fullTextSearch.K, com.lightningkite.lightningdb.fullTextSearch.V
function xCMBuilderFullTextSearch(this_, value, ignoreCase) {
    return this_.mapCondition(new Condition_1.Condition.FullTextSearch(value, ignoreCase));
}
exports.xCMBuilderFullTextSearch = xCMBuilderFullTextSearch;
//! Declares com.lightningkite.lightningdb.all>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.all.K, kotlin.collections.Listcom.lightningkite.lightningdb.all.T
function xCMBuilderListAll(this_, condition) {
    return this_.mapCondition(new Condition_1.Condition.ListAllElements((condition)(path())));
}
exports.xCMBuilderListAll = xCMBuilderListAll;
//! Declares com.lightningkite.lightningdb.any>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.any.K, kotlin.collections.Listcom.lightningkite.lightningdb.any.T
function xCMBuilderListAny(this_, condition) {
    return this_.mapCondition(new Condition_1.Condition.ListAnyElements((condition)(path())));
}
exports.xCMBuilderListAny = xCMBuilderListAny;
//! Declares com.lightningkite.lightningdb.sizesEquals>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.sizesEquals.K, kotlin.collections.Listcom.lightningkite.lightningdb.sizesEquals.T
function xCMBuilderListSizedEqual(this_, count) {
    return this_.mapCondition(new Condition_1.Condition.ListSizesEquals(count));
}
exports.xCMBuilderListSizedEqual = xCMBuilderListSizedEqual;
//! Declares com.lightningkite.lightningdb.all>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.all.K, kotlin.collections.Setcom.lightningkite.lightningdb.all.T
function xCMBuilderSetAll(this_, condition) {
    return this_.mapCondition(new Condition_1.Condition.SetAllElements((condition)(path())));
}
exports.xCMBuilderSetAll = xCMBuilderSetAll;
//! Declares com.lightningkite.lightningdb.any>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.any.K, kotlin.collections.Setcom.lightningkite.lightningdb.any.T
function xCMBuilderSetAny(this_, condition) {
    return this_.mapCondition(new Condition_1.Condition.SetAnyElements((condition)(path())));
}
exports.xCMBuilderSetAny = xCMBuilderSetAny;
//! Declares com.lightningkite.lightningdb.sizesEquals>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.sizesEquals.K, kotlin.collections.Setcom.lightningkite.lightningdb.sizesEquals.T
function xCMBuilderSetSizedEqual(this_, count) {
    return this_.mapCondition(new Condition_1.Condition.SetSizesEquals(count));
}
exports.xCMBuilderSetSizedEqual = xCMBuilderSetSizedEqual;
//! Declares com.lightningkite.lightningdb.containsKey>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.containsKey.K, kotlin.collections.Mapkotlin.String, com.lightningkite.lightningdb.containsKey.T
function xCMBuilderContainsKey(this_, key) {
    return this_.mapCondition(new Condition_1.Condition.Exists(key));
}
exports.xCMBuilderContainsKey = xCMBuilderContainsKey;
//! Declares com.lightningkite.lightningdb.condition>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.condition.K, com.lightningkite.lightningdb.condition.T
function xCMBuilderCondition(this_, make) {
    return this_.mapCondition(make(path()));
}
exports.xCMBuilderCondition = xCMBuilderCondition;
//////////////
//! Declares com.lightningkite.lightningdb.notNull>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.notNull.K, com.lightningkite.lightningdb.notNull.T
function xCMBuilderNotNullGet(this_) { return new CMBuilder((it) => (this_.mapCondition(new Condition_1.Condition.IfNotNull(it))), (it) => (this_.mapModification(new Modification_1.Modification.IfNotNull(it)))); }
exports.xCMBuilderNotNullGet = xCMBuilderNotNullGet;
//! Declares com.lightningkite.lightningdb.get>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.get.K, kotlin.collections.Mapkotlin.String, com.lightningkite.lightningdb.get.T
function xCMBuilderGet(this_, key) {
    return new CMBuilder((it) => (this_.mapCondition(new Condition_1.Condition.OnKey(key, it))), (it) => (this_.mapModification(new Modification_1.Modification.ModifyByKey(new Map([[key, it]])))));
}
exports.xCMBuilderGet = xCMBuilderGet;
//! Declares com.lightningkite.lightningdb.all>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.all.K, kotlin.collections.Listcom.lightningkite.lightningdb.all.T
function xCMBuilderListAllGet(this_) { return new CMBuilder((it) => (this_.mapCondition(new Condition_1.Condition.ListAllElements(it))), (it) => (this_.mapModification(new Modification_1.Modification.ListPerElement(new Condition_1.Condition.Always(), it)))); }
exports.xCMBuilderListAllGet = xCMBuilderListAllGet;
//! Declares com.lightningkite.lightningdb.all>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.all.K, kotlin.collections.Setcom.lightningkite.lightningdb.all.T
function xCMBuilderSetAllGet(this_) { return new CMBuilder((it) => (this_.mapCondition(new Condition_1.Condition.SetAllElements(it))), (it) => (this_.mapModification(new Modification_1.Modification.SetPerElement(new Condition_1.Condition.Always(), it)))); }
exports.xCMBuilderSetAllGet = xCMBuilderSetAllGet;
//! Declares com.lightningkite.lightningdb.any>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.any.K, kotlin.collections.Listcom.lightningkite.lightningdb.any.T
function xCMBuilderListAnyGet(this_) { return new CMBuilder((it) => (this_.mapCondition(new Condition_1.Condition.ListAnyElements(it))), (it) => (this_.mapModification(new Modification_1.Modification.ListPerElement(new Condition_1.Condition.Always(), it)))); }
exports.xCMBuilderListAnyGet = xCMBuilderListAnyGet;
//! Declares com.lightningkite.lightningdb.any>com.lightningkite.lightningdb.CMBuildercom.lightningkite.lightningdb.any.K, kotlin.collections.Setcom.lightningkite.lightningdb.any.T
function xCMBuilderSetAnyGet(this_) { return new CMBuilder((it) => (this_.mapCondition(new Condition_1.Condition.SetAnyElements(it))), (it) => (this_.mapModification(new Modification_1.Modification.SetPerElement(new Condition_1.Condition.Always(), it)))); }
exports.xCMBuilderSetAnyGet = xCMBuilderSetAnyGet;
//! Declares com.lightningkite.lightningdb.then>kotlin.Unit
function xUnitThen(this_, ignored) {
    return undefined;
}
exports.xUnitThen = xUnitThen;
//# sourceMappingURL=ConditionBuilder.js.map