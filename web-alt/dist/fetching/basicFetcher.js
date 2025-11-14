"use strict";
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
    return async function (path, method, body) {
        return apiCall(`${baseUrl}/${path}`, body, { method, headers: await additionalHeaders() }, responseInterceptors)
            .then((x) => x.json())
            .catch((e) => undefined);
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