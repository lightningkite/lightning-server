"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.apiCall = void 0;
function apiCall(url, body, request, fileUploads, responseInterceptors) {
    let f;
    if (fileUploads === undefined || Object.keys(fileUploads).length === 0) {
        f = fetch(url, Object.assign(Object.assign({}, request), { headers: Object.assign(Object.assign({}, request.headers), { "Content-Type": "application/json", "Accept": "application/json" }), body: JSON.stringify(body) }));
    }
    else {
        const data = new FormData();
        data.append("__json", JSON.stringify(body));
        for (const key in fileUploads) {
            data.append(key, fileUploads[key], fileUploads[key].name);
        }
        f = fetch(url, Object.assign(Object.assign({}, request), { headers: Object.assign(Object.assign({}, request.headers), { "Accept": "application/json" }), body: data }));
    }
    return f.then(x => {
        var _a;
        let response = (_a = responseInterceptors === null || responseInterceptors === void 0 ? void 0 : responseInterceptors.call(x)) !== null && _a !== void 0 ? _a : x;
        if (!response.ok) {
            throw response;
        }
        else
            return response;
    });
}
exports.apiCall = apiCall;
//# sourceMappingURL=apiCall.js.map