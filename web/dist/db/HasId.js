"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.HasPhoneNumberFields = exports.HasEmailFields = exports.HasIdFields = void 0;
//! Declares com.lightningkite.lightningdb.HasIdFields
class HasIdFields {
    constructor() {
    }
    _id() {
        return "_id";
    }
}
exports.HasIdFields = HasIdFields;
HasIdFields.INSTANCE = new HasIdFields();
//! Declares com.lightningkite.lightningdb.HasEmailFields
class HasEmailFields {
    constructor() {
    }
    email() {
        return "email";
    }
}
exports.HasEmailFields = HasEmailFields;
HasEmailFields.INSTANCE = new HasEmailFields();
//! Declares com.lightningkite.lightningdb.HasPhoneNumberFields
class HasPhoneNumberFields {
    constructor() {
    }
    phoneNumber() {
        return "phoneNumber";
    }
}
exports.HasPhoneNumberFields = HasPhoneNumberFields;
HasPhoneNumberFields.INSTANCE = new HasPhoneNumberFields();
//# sourceMappingURL=HasId.js.map