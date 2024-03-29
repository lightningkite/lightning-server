"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.LSError = void 0;
// Package: com.lightningkite.lightningserver
// Generated by Khrysalis - this file will be overwritten.
const khrysalis_runtime_1 = require("@lightningkite/khrysalis-runtime");
//! Declares com.lightningkite.lightningserver.LSError
class LSError {
    constructor(http, detail = "", message = "", data = "") {
        this.http = http;
        this.detail = detail;
        this.message = message;
        this.data = data;
    }
    static propertyTypes() { return { http: [Number], detail: [String], message: [String], data: [String] }; }
}
exports.LSError = LSError;
LSError.properties = ["http", "detail", "message", "data"];
(0, khrysalis_runtime_1.setUpDataClass)(LSError);
//# sourceMappingURL=LSError.js.map