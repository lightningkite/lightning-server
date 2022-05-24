"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.HasEmailFields = exports.HasIdFields = exports.HasId = void 0;
//! Declares com.lightningkite.ktordb.HasId
class HasId {
    constructor() {
    }
}
exports.HasId = HasId;
//! Declares com.lightningkite.ktordb.HasIdFields
class HasIdFields {
    constructor() {
    }
    _id() {
        return "_id";
    }
}
exports.HasIdFields = HasIdFields;
HasIdFields.INSTANCE = new HasIdFields();
//! Declares com.lightningkite.ktordb.HasEmailFields
class HasEmailFields {
    constructor() {
    }
    email() {
        return "email";
    }
}
exports.HasEmailFields = HasEmailFields;
HasEmailFields.INSTANCE = new HasEmailFields();
//# sourceMappingURL=HasId.js.map