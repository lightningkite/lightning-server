"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.PhonePinLogin = exports.EmailPinLogin = void 0;
// Package: com.lightningkite.lightningserver.auth
// Generated by Khrysalis - this file will be overwritten.
const khrysalis_runtime_1 = require("@lightningkite/khrysalis-runtime");
//! Declares com.lightningkite.lightningserver.auth.EmailPinLogin
class EmailPinLogin {
    constructor(email, pin) {
        this.email = email;
        this.pin = pin;
    }
    static propertyTypes() { return { email: [String], pin: [String] }; }
}
exports.EmailPinLogin = EmailPinLogin;
EmailPinLogin.properties = ["email", "pin"];
(0, khrysalis_runtime_1.setUpDataClass)(EmailPinLogin);
//! Declares com.lightningkite.lightningserver.auth.PhonePinLogin
class PhonePinLogin {
    constructor(phone, pin) {
        this.phone = phone;
        this.pin = pin;
    }
    static propertyTypes() { return { phone: [String], pin: [String] }; }
}
exports.PhonePinLogin = PhonePinLogin;
PhonePinLogin.properties = ["phone", "pin"];
(0, khrysalis_runtime_1.setUpDataClass)(PhonePinLogin);
//# sourceMappingURL=EmailPinLogin.js.map