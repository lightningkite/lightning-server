"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.LiveCompleteModelApi = void 0;
const CompleteModelApi_1 = require("../CompleteModelApi");
const LiveObserveModelApi_1 = require("./LiveObserveModelApi");
const LiveReadModelApi_1 = require("./LiveReadModelApi");
const LiveWriteModelApi_1 = require("./LiveWriteModelApi");
//! Declares com.lightningkite.lightningdb.live.LiveCompleteModelApi
class LiveCompleteModelApi extends CompleteModelApi_1.CompleteModelApi {
    constructor(read, write, observe) {
        super();
        this.read = read;
        this.write = write;
        this.observe = observe;
    }
}
exports.LiveCompleteModelApi = LiveCompleteModelApi;
(function (LiveCompleteModelApi) {
    //! Declares com.lightningkite.lightningdb.live.LiveCompleteModelApi.Companion
    class Companion {
        constructor() {
        }
        create(Model, root, multiplexSocketUrl, path, token) {
            return new LiveCompleteModelApi(new LiveReadModelApi_1.LiveReadModelApi(`${root}${path}`, token, Model), new LiveWriteModelApi_1.LiveWriteModelApi(`${root}${path}`, token, Model), LiveObserveModelApi_1.LiveObserveModelApi.Companion.INSTANCE.create(Model, multiplexSocketUrl, token, path));
        }
    }
    Companion.INSTANCE = new Companion();
    LiveCompleteModelApi.Companion = Companion;
})(LiveCompleteModelApi = exports.LiveCompleteModelApi || (exports.LiveCompleteModelApi = {}));
//# sourceMappingURL=LiveCompleteModelApi.js.map