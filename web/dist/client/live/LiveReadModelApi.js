"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.LiveReadModelApi = void 0;
const ReadModelApi_1 = require("../ReadModelApi");
const rxjs_plus_1 = require("@lightningkite/rxjs-plus");
//! Declares com.lightningkite.lightningdb.live.LiveReadModelApi
class LiveReadModelApi extends ReadModelApi_1.ReadModelApi {
    constructor(url, token, serializer) {
        super();
        this.url = url;
        this.token = token;
        this.serializer = serializer;
    }
    list(query) {
        return rxjs_plus_1.HttpClient.INSTANCE.call(`${this.url}/query`, rxjs_plus_1.HttpClient.INSTANCE.POST, new Map([["Authorization", `Bearer ${this.token}`]]), rxjs_plus_1.HttpBody.json(query), undefined).pipe(rxjs_plus_1.unsuccessfulAsError, (0, rxjs_plus_1.fromJSON)([Array, this.serializer]));
    }
    get(id) {
        return rxjs_plus_1.HttpClient.INSTANCE.call(`${this.url}/${id}`, rxjs_plus_1.HttpClient.INSTANCE.GET, new Map([["Authorization", `Bearer ${this.token}`]]), undefined, undefined).pipe(rxjs_plus_1.unsuccessfulAsError, (0, rxjs_plus_1.fromJSON)(this.serializer));
    }
}
exports.LiveReadModelApi = LiveReadModelApi;
//# sourceMappingURL=LiveReadModelApi.js.map