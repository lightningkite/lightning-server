"use strict";
var __awaiter = (this && this.__awaiter) || function (thisArg, _arguments, P, generator) {
    function adopt(value) { return value instanceof P ? value : new P(function (resolve) { resolve(value); }); }
    return new (P || (P = Promise))(function (resolve, reject) {
        function fulfilled(value) { try { step(generator.next(value)); } catch (e) { reject(e); } }
        function rejected(value) { try { step(generator["throw"](value)); } catch (e) { reject(e); } }
        function step(result) { result.done ? resolve(result.value) : adopt(result.value).then(fulfilled, rejected); }
        step((generator = generator.apply(thisArg, _arguments || [])).next());
    });
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.createBulkFetcher = exports.LsErrorException = exports.BulkFetcher = void 0;
class BulkFetcher {
    constructor(serverUrl, headerCalculator, responseInterceptor, notBatchable, delayMs) {
        this.serverUrl = serverUrl;
        this.headerCalculator = headerCalculator;
        this.responseInterceptor = responseInterceptor;
        this.notBatchable = notBatchable;
        this.delayMs = delayMs;
        this.fetchQueue = new Map();
        this.scheduled = false;
        this.fetch = (path, method, body) => __awaiter(this, void 0, void 0, function* () {
            const id = crypto.randomUUID();
            if (this.notBatchable(path, method, body)) {
                const headers = yield this.headerCalculator();
                headers["Content-Type"] = "application/json";
                return fetch(`${this.serverUrl}/${path}`, {
                    method: method,
                    body: JSON.stringify(body),
                    headers: headers,
                })
                    .then((res) => {
                    var _a;
                    (_a = this.responseInterceptor) === null || _a === void 0 ? void 0 : _a.call(this, res);
                    return res.json();
                })
                    .then((r) => r);
            }
            return new Promise((resolve, reject) => {
                this.fetchQueue.set(id, {
                    request: {
                        path,
                        method,
                        body: JSON.stringify(body),
                    },
                    resolve,
                    reject,
                });
                if (!this.scheduled) {
                    this.scheduled = true;
                    setTimeout(() => this.executeFetch(), this.delayMs);
                }
            }).then((res) => {
                if (res.error) {
                    throw new LsErrorException(res.error.http, res.error);
                }
                else if (res.result !== undefined) {
                    return JSON.parse(res.result);
                }
                else {
                    return undefined; // for void return types
                }
            });
        });
    }
    executeFetch() {
        var _a;
        return __awaiter(this, void 0, void 0, function* () {
            const batch = new Map(this.fetchQueue);
            this.fetchQueue.clear();
            this.scheduled = false;
            const headers = yield this.headerCalculator();
            headers["Content-Type"] = "application/json";
            headers["Accept"] = "application/json";
            const requestBody = JSON.stringify(Object.fromEntries([...batch.entries()].map(([id, entry]) => [id, entry.request])));
            try {
                const response = yield fetch(`${this.serverUrl}/meta/bulk`, {
                    method: "POST",
                    headers,
                    body: requestBody,
                });
                (_a = this.responseInterceptor) === null || _a === void 0 ? void 0 : _a.call(this, response);
                if (!response.ok) {
                    const errorText = yield response.text();
                    batch.forEach(({ reject }) => reject(new Error(`${response.status}: ${errorText}`)));
                    return;
                }
                const responses = yield response.json();
                batch.forEach(({ resolve, reject }, id) => {
                    const res = responses[id];
                    if (res) {
                        resolve(res);
                    }
                    else {
                        reject(new Error(`Bulk key ${id} not found`));
                    }
                });
            }
            catch (e) {
                batch.forEach(({ reject }) => reject(e));
            }
        });
    }
}
exports.BulkFetcher = BulkFetcher;
class LsErrorException extends Error {
    constructor(httpCode, error) {
        var _a;
        super((_a = error.message) !== null && _a !== void 0 ? _a : `HTTP Error ${httpCode}`);
        this.httpCode = httpCode;
        this.error = error;
    }
}
exports.LsErrorException = LsErrorException;
/**
 * Creates a bulk fetcher instance that batches HTTP requests for efficiency.
 *
 * @param params - Configuration options for the bulk fetcher.
 * @returns A `Fetcher` function that behaves like `fetch`, but supports batching.
 */
function createBulkFetcher(params) {
    const { serverUrl, headerCalculator = () => ({}), responseInterceptors = (r) => r, notBatchable = () => false, delayMs = 100, } = params;
    return new BulkFetcher(serverUrl, headerCalculator, responseInterceptors, notBatchable, delayMs).fetch;
}
exports.createBulkFetcher = createBulkFetcher;
//# sourceMappingURL=bulkFetcher.js.map