"use strict";
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
        async query(input) {
            return endpoint.query(input).then(addAnnotations);
        },
        async detail(id) {
            const item = await endpoint.detail(id);
            return (await addAnnotations([item]))[0];
        },
        async bulkDelete(input) {
            return endpoint.bulkDelete(input);
        },
        async delete(id) {
            return endpoint.delete(id);
        },
        async count(input) {
            return endpoint.count(input);
        },
    };
}
exports.annotateEndpoint = annotateEndpoint;
//# sourceMappingURL=annotateEndpoint.js.map