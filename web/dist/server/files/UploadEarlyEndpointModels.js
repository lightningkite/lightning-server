"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.UploadInformation = exports.UploadForNextRequest = void 0;
const core_1 = require("@js-joda/core");
const khrysalis_runtime_1 = require("@lightningkite/khrysalis-runtime");
const uuid_1 = require("uuid");
//! Declares com.lightningkite.lightningserver.files.UploadForNextRequest
class UploadForNextRequest {
    constructor(_id = (0, uuid_1.v4)(), file, expires = core_1.Instant.now().plus(core_1.Duration.ofMinutes(15))) {
        this._id = _id;
        this.file = file;
        this.expires = expires;
    }
    static propertyTypes() { return { _id: [String], file: [String], expires: [core_1.Instant] }; }
}
exports.UploadForNextRequest = UploadForNextRequest;
UploadForNextRequest.implementsHasId = true;
UploadForNextRequest.properties = ["_id", "file", "expires"];
(0, khrysalis_runtime_1.setUpDataClass)(UploadForNextRequest);
//! Declares com.lightningkite.lightningserver.files.UploadInformation
class UploadInformation {
    constructor(uploadUrl, futureCallToken) {
        this.uploadUrl = uploadUrl;
        this.futureCallToken = futureCallToken;
    }
    static propertyTypes() { return { uploadUrl: [String], futureCallToken: [String] }; }
}
exports.UploadInformation = UploadInformation;
UploadInformation.properties = ["uploadUrl", "futureCallToken"];
(0, khrysalis_runtime_1.setUpDataClass)(UploadInformation);
//# sourceMappingURL=UploadEarlyEndpointModels.js.map