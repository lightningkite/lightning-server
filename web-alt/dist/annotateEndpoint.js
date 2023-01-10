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
var __rest = (this && this.__rest) || function (s, e) {
    var t = {};
    for (var p in s) if (Object.prototype.hasOwnProperty.call(s, p) && e.indexOf(p) < 0)
        t[p] = s[p];
    if (s != null && typeof Object.getOwnPropertySymbols === "function")
        for (var i = 0, p = Object.getOwnPropertySymbols(s); i < p.length; i++) {
            if (e.indexOf(p[i]) < 0 && Object.prototype.propertyIsEnumerable.call(s, p[i]))
                t[p[i]] = s[p[i]];
        }
    return t;
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.annotateEndpoint = void 0;
/**
 * Annotates the data returned by an endpoint with additional data.
 *
 * @param endpoint the restEndpoint to annotate
 * @param addAnnotations a function that takes an array of items and returns an array of the same length with annotations added
 * @returns a new endpoint that returns the same data as the original endpoint, but with annotations added
 */
function annotateEndpoint(endpoint, addAnnotations) {
    return {
        default() {
            return __awaiter(this, void 0, void 0, function* () {
                const item = yield endpoint.default();
                return (yield addAnnotations([item]))[0];
            });
        },
        query(input) {
            return __awaiter(this, void 0, void 0, function* () {
                return endpoint.query(input).then(addAnnotations);
            });
        },
        detail(id) {
            return __awaiter(this, void 0, void 0, function* () {
                const item = yield endpoint.detail(id);
                return (yield addAnnotations([item]))[0];
            });
        },
        insertBulk(input) {
            return __awaiter(this, void 0, void 0, function* () {
                const items = yield endpoint.insertBulk(stripAnnotationsArray(input));
                return yield addAnnotations(items);
            });
        },
        insert(input) {
            return __awaiter(this, void 0, void 0, function* () {
                const item = yield endpoint.insert(stripAnnotations(input));
                return (yield addAnnotations([item]))[0];
            });
        },
        upsert(id, input) {
            return __awaiter(this, void 0, void 0, function* () {
                const item = yield endpoint.upsert(id, input);
                return (yield addAnnotations([item]))[0];
            });
        },
        bulkReplace(input) {
            return __awaiter(this, void 0, void 0, function* () {
                const items = yield endpoint.bulkReplace(stripAnnotationsArray(input));
                return yield addAnnotations(items);
            });
        },
        replace(id, input) {
            return __awaiter(this, void 0, void 0, function* () {
                const item = yield endpoint.replace(id, input);
                return (yield addAnnotations([item]))[0];
            });
        },
        bulkModify(input) {
            return __awaiter(this, void 0, void 0, function* () {
                return endpoint.bulkModify(input);
            });
        },
        modifyWithDiff(id, input) {
            return __awaiter(this, void 0, void 0, function* () {
                return Promise.reject("Not implemented with annotations");
            });
        },
        modify(id, input) {
            return __awaiter(this, void 0, void 0, function* () {
                const item = yield endpoint.modify(id, input);
                return (yield addAnnotations([item]))[0];
            });
        },
        bulkDelete(input) {
            return __awaiter(this, void 0, void 0, function* () {
                return endpoint.bulkDelete(input);
            });
        },
        delete(id) {
            return __awaiter(this, void 0, void 0, function* () {
                return endpoint.delete(id);
            });
        },
        count(input) {
            return __awaiter(this, void 0, void 0, function* () {
                return endpoint.count(input);
            });
        },
        groupCount(input) {
            return __awaiter(this, void 0, void 0, function* () {
                return endpoint.groupCount(input);
            });
        },
        aggregate(input) {
            return __awaiter(this, void 0, void 0, function* () {
                return endpoint.aggregate(input);
            });
        },
        groupAggregate(input) {
            return __awaiter(this, void 0, void 0, function* () {
                return endpoint.groupAggregate(input);
            });
        },
    };
}
exports.annotateEndpoint = annotateEndpoint;
function stripAnnotationsArray(items) {
    return items.map((item) => {
        const { annotations } = item, rest = __rest(item, ["annotations"]);
        return rest;
    });
}
function stripAnnotations(item) {
    const { annotations } = item, rest = __rest(item, ["annotations"]);
    return rest;
}
//# sourceMappingURL=annotateEndpoint.js.map