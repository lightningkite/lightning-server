"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.excessiveQueryLimitSize = void 0;
/**
 *
 * @param maxLimit Highest 'limit' in a query allowed
 * @returns a function for determining whether the query is above the 'maxLimit'
 */
const excessiveQueryLimitSize = (maxLimit = 100) => (_, __, body) => {
    if (typeof body === "object" && body !== null) {
        if ("limit" in body) {
            if (typeof body.limit === "number") {
                return body.limit > 100;
            }
        }
    }
    return false;
};
exports.excessiveQueryLimitSize = excessiveQueryLimitSize;
//# sourceMappingURL=helpers.js.map