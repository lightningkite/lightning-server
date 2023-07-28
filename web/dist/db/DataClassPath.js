"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.xDataClassPathNotNullGet = exports.DataClassPathNotNull = exports.DataClassPathAccess = exports.DataClassPathSelf = exports.DataClassPath = exports.DataClassPathPartial = void 0;
// Package: com.lightningkite.lightningdb
// Generated by Khrysalis, then slightly customized
const Condition_1 = require("./Condition");
const Modification_1 = require("./Modification");
const TProperty1Extensions_1 = require("./TProperty1Extensions");
const khrysalis_runtime_1 = require("@lightningkite/khrysalis-runtime");
//! Declares com.lightningkite.lightningdb.DataClassPathPartial
class DataClassPathPartial {
    constructor() {
    }
}
exports.DataClassPathPartial = DataClassPathPartial;
//! Declares com.lightningkite.lightningdb.DataClassPath
class DataClassPath extends DataClassPathPartial {
    constructor() {
        super();
    }
    getAny(key) {
        return this.get(key);
    }
    setAny(key, any) {
        return this.set(key, any);
    }
    prop(prop) {
        return new DataClassPathAccess(this, prop);
    }
    notNull() {
        // @ts-ignore
        return new DataClassPathNotNull(this);
    }
}
exports.DataClassPath = DataClassPath;
//! Declares com.lightningkite.lightningdb.DataClassPathSelf
class DataClassPathSelf extends DataClassPath {
    constructor() {
        super();
    }
    get(key) {
        return key;
    }
    set(key, value) {
        return value;
    }
    toString() {
        return "this";
    }
    hashCode() {
        return 0;
    }
    equals(other) {
        return other instanceof DataClassPathSelf;
    }
    //! Declares com.lightningkite.lightningdb.DataClassPathSelf.properties
    get properties() { return []; }
    mapCondition(condition) {
        return condition;
    }
    mapModification(modification) {
        return modification;
    }
}
exports.DataClassPathSelf = DataClassPathSelf;
//! Declares com.lightningkite.lightningdb.DataClassPathAccess
class DataClassPathAccess extends DataClassPath {
    constructor(first, second) {
        super();
        this.first = first;
        this.second = second;
    }
    static propertyTypes(K, M, V) { return { first: [DataClassPath, K, M], second: [String, M, V] }; }
    get(key) {
        return (() => {
            const temp0 = this.first.get(key);
            if (temp0 === null || temp0 === undefined) {
                return null;
            }
            return ((it) => ((0, khrysalis_runtime_1.reflectiveGet)(it, this.second)))(temp0);
        })();
    }
    set(key, value) {
        var _a;
        return (_a = (() => {
            const temp3 = this.first.get(key);
            if (temp3 === null || temp3 === undefined) {
                return null;
            }
            return ((it) => (this.first.set(key, (0, TProperty1Extensions_1.keySet)(it, this.second, value))))(temp3);
        })()) !== null && _a !== void 0 ? _a : key;
    }
    toString() {
        return this.first instanceof DataClassPathSelf ? this.second : `${this.first}.${this.second}`;
    }
    //! Declares com.lightningkite.lightningdb.DataClassPathAccess.properties
    get properties() { return this.first.properties.concat([this.second]); }
    mapCondition(condition) {
        return this.first.mapCondition(new Condition_1.Condition.OnField(this.second, condition));
    }
    mapModification(modification) {
        return this.first.mapModification(new Modification_1.Modification.OnField(this.second, modification));
    }
}
exports.DataClassPathAccess = DataClassPathAccess;
DataClassPathAccess.properties = ["first", "second"];
(0, khrysalis_runtime_1.setUpDataClass)(DataClassPathAccess);
//! Declares com.lightningkite.lightningdb.DataClassPathNotNull
class DataClassPathNotNull extends DataClassPath {
    constructor(wraps) {
        super();
        this.wraps = wraps;
    }
    static propertyTypes(K, V) { return { wraps: [DataClassPath, K, V] }; }
    //! Declares com.lightningkite.lightningdb.DataClassPathNotNull.properties
    get properties() { return this.wraps.properties; }
    get(key) {
        return this.wraps.get(key);
    }
    set(key, value) {
        return this.wraps.set(key, value);
    }
    toString() {
        return `${this.wraps}?`;
    }
    mapCondition(condition) {
        return this.wraps.mapCondition(new Condition_1.Condition.IfNotNull(condition));
    }
    mapModification(modification) {
        return this.wraps.mapModification(new Modification_1.Modification.IfNotNull(modification));
    }
}
exports.DataClassPathNotNull = DataClassPathNotNull;
DataClassPathNotNull.properties = ["wraps"];
(0, khrysalis_runtime_1.setUpDataClass)(DataClassPathNotNull);
//! Declares com.lightningkite.lightningdb.notNull>com.lightningkite.lightningdb.DataClassPathcom.lightningkite.lightningdb.notNull.K, com.lightningkite.lightningdb.notNull.V
function xDataClassPathNotNullGet(this_) { return new DataClassPathNotNull(this_); }
exports.xDataClassPathNotNullGet = xDataClassPathNotNullGet;
//# sourceMappingURL=DataClassPath.js.map