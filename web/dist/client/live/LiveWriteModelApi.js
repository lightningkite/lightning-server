"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.LiveWriteModelApi = void 0;
const WriteModelApi_1 = require("../WriteModelApi");
const rxjs_plus_1 = require("@lightningkite/rxjs-plus");
const rxjs_1 = require("rxjs");
//! Declares com.lightningkite.lightningdb.live.LiveWriteModelApi
class LiveWriteModelApi extends WriteModelApi_1.WriteModelApi {
    constructor(url, token, serializer) {
        super();
        this.url = url;
        this.token = token;
        this.serializer = serializer;
    }
    post(value) {
        return rxjs_plus_1.HttpClient.INSTANCE.call(this.url, rxjs_plus_1.HttpClient.INSTANCE.POST, new Map([["Authorization", `Bearer ${this.token}`]]), rxjs_plus_1.HttpBody.json(value), undefined).pipe(rxjs_plus_1.unsuccessfulAsError, (0, rxjs_plus_1.fromJSON)(this.serializer));
    }
    postBulk(values) {
        return rxjs_plus_1.HttpClient.INSTANCE.call(`${this.url}/bulk`, rxjs_plus_1.HttpClient.INSTANCE.POST, new Map([["Authorization", `Bearer ${this.token}`]]), rxjs_plus_1.HttpBody.json(values), undefined).pipe(rxjs_plus_1.unsuccessfulAsError, (0, rxjs_plus_1.fromJSON)([Array, this.serializer]));
    }
    put(value) {
        return rxjs_plus_1.HttpClient.INSTANCE.call(`${this.url}/${value._id}`, rxjs_plus_1.HttpClient.INSTANCE.PUT, new Map([["Authorization", `Bearer ${this.token}`]]), rxjs_plus_1.HttpBody.json(value), undefined).pipe(rxjs_plus_1.unsuccessfulAsError, (0, rxjs_plus_1.fromJSON)(this.serializer));
    }
    putBulk(values) {
        return rxjs_plus_1.HttpClient.INSTANCE.call(`${this.url}/bulk`, rxjs_plus_1.HttpClient.INSTANCE.PUT, new Map([["Authorization", `Bearer ${this.token}`]]), rxjs_plus_1.HttpBody.json(values), undefined).pipe(rxjs_plus_1.unsuccessfulAsError, (0, rxjs_plus_1.fromJSON)([Array, this.serializer]));
    }
    patch(id, modification) {
        return rxjs_plus_1.HttpClient.INSTANCE.call(`${this.url}/${id}`, rxjs_plus_1.HttpClient.INSTANCE.PATCH, new Map([["Authorization", `Bearer ${this.token}`]]), rxjs_plus_1.HttpBody.json(modification), undefined).pipe(rxjs_plus_1.unsuccessfulAsError, (0, rxjs_plus_1.fromJSON)(this.serializer));
    }
    patchBulk(modification) {
        return rxjs_plus_1.HttpClient.INSTANCE.call(`${this.url}/bulk`, rxjs_plus_1.HttpClient.INSTANCE.PATCH, new Map([["Authorization", `Bearer ${this.token}`]]), rxjs_plus_1.HttpBody.json(modification), undefined).pipe(rxjs_plus_1.unsuccessfulAsError, (0, rxjs_plus_1.fromJSON)([Array, this.serializer]));
    }
    _delete(id) {
        return rxjs_plus_1.HttpClient.INSTANCE.call(`${this.url}/${id}`, rxjs_plus_1.HttpClient.INSTANCE.DELETE, new Map([["Authorization", `Bearer ${this.token}`]]), undefined, undefined).pipe(rxjs_plus_1.unsuccessfulAsError, (0, rxjs_1.switchMap)(x => x.text().then(x => undefined)));
    }
    deleteBulk(condition) {
        return rxjs_plus_1.HttpClient.INSTANCE.call(`${this.url}/bulk`, rxjs_plus_1.HttpClient.INSTANCE.DELETE, new Map([["Authorization", `Bearer ${this.token}`]]), rxjs_plus_1.HttpBody.json(condition), undefined).pipe(rxjs_plus_1.unsuccessfulAsError, (0, rxjs_1.switchMap)(x => x.text().then(x => undefined)));
    }
}
exports.LiveWriteModelApi = LiveWriteModelApi;
//# sourceMappingURL=LiveWriteModelApi.js.map