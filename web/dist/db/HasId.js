"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.HasPasswordFields = exports.HasMaybePhoneNumberFields = exports.HasMaybeEmailFields = exports.HasPhoneNumberFields = exports.HasEmailFields = exports.HasIdFields = void 0;
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
//! Declares com.lightningkite.lightningdb.HasMaybeEmailFields
class HasMaybeEmailFields {
    constructor() {
    }
    email() {
        return "email";
    }
}
exports.HasMaybeEmailFields = HasMaybeEmailFields;
HasMaybeEmailFields.INSTANCE = new HasMaybeEmailFields();
//! Declares com.lightningkite.lightningdb.HasMaybePhoneNumberFields
class HasMaybePhoneNumberFields {
    constructor() {
    }
    phoneNumber() {
        return "phoneNumber";
    }
}
exports.HasMaybePhoneNumberFields = HasMaybePhoneNumberFields;
HasMaybePhoneNumberFields.INSTANCE = new HasMaybePhoneNumberFields();
//! Declares com.lightningkite.lightningdb.HasPasswordFields
class HasPasswordFields {
    constructor() {
    }
    hashedPassword() {
        return "hashedPassword";
    }
}
exports.HasPasswordFields = HasPasswordFields;
HasPasswordFields.INSTANCE = new HasPasswordFields();
//# sourceMappingURL=HasId.js.map