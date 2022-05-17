"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.LiveFullReadModelApi = void 0;
const FullReadModelApi_1 = require("../FullReadModelApi");
const LiveObserveModelApi_1 = require("./LiveObserveModelApi");
const LiveReadModelApi_1 = require("./LiveReadModelApi");
//! Declares com.lightningkite.ktordb.live.LiveFullReadModelApi
class LiveFullReadModelApi extends FullReadModelApi_1.FullReadModelApi {
    constructor(read, observe) {
        super();
        this.read = read;
        this.observe = observe;
    }
}
exports.LiveFullReadModelApi = LiveFullReadModelApi;
(function (LiveFullReadModelApi) {
    //! Declares com.lightningkite.ktordb.live.LiveFullReadModelApi.Companion
    class Companion {
        constructor() {
        }
        create(Model, root, multiplexSocketUrl, path, token) {
            return new LiveFullReadModelApi(new LiveReadModelApi_1.LiveReadModelApi(`${root}${path}`, token, Model), LiveObserveModelApi_1.LiveObserveModelApi.Companion.INSTANCE.create(Model, multiplexSocketUrl, token, path));
        }
    }
    Companion.INSTANCE = new Companion();
    LiveFullReadModelApi.Companion = Companion;
})(LiveFullReadModelApi = exports.LiveFullReadModelApi || (exports.LiveFullReadModelApi = {}));
//# sourceMappingURL=LiveFullReadModelApi.js.map