"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.apiCall = void 0;
function apiCall(url, body, request, fileUploads) {
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
        if (!x.ok)
            throw x;
        else
            return x;
    });
}
exports.apiCall = apiCall;
//# sourceMappingURL=apiCall.js.map