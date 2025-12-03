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
exports.createBasicFetcher = void 0;
/**
 * Creates a fetcher for making api calls
 * @param baseUrl Server URL
 * @param additionalHeaders Optional function for computing headers for each request. Defaults to () => ({})
 * @param responseInterceptors Intercepts responses before they are resolved.
 * @returns a 'Fetcher' function for making requests
 */
function createBasicFetcher(baseUrl, additionalHeaders = () => ({}), responseInterceptors) {
    return function (path, method, body) {
        return __awaiter(this, void 0, void 0, function* () {
            return apiCall(`${baseUrl}/${path}`, body, { method, headers: yield additionalHeaders() }, responseInterceptors).then((x) => __awaiter(this, void 0, void 0, function* () {
                try {
                    return yield x.json();
                }
                catch (e) {
                    // When the response is 'void'
                    return undefined;
                }
            }));
        });
    };
}
exports.createBasicFetcher = createBasicFetcher;
function apiCall(url, body, request, responseInterceptors) {
    return fetch(url, Object.assign(Object.assign({}, request), { headers: Object.assign(Object.assign({}, request.headers), { "Content-Type": "application/json", Accept: "application/json" }), body: JSON.stringify(body) })).then((x) => {
        var _a;
        const response = (_a = responseInterceptors === null || responseInterceptors === void 0 ? void 0 : responseInterceptors(x)) !== null && _a !== void 0 ? _a : x;
        if (!response.ok) {
            throw response;
        }
        else
            return response;
    });
}
//# sourceMappingURL=basicFetcher.js.map