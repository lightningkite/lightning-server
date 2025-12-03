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
    };
}
exports.annotateEndpoint = annotateEndpoint;
//# sourceMappingURL=annotateEndpoint.js.map