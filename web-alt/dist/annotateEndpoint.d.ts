import { HasId, SessionRestEndpoint } from "sessionRest";
export declare type WithAnnotations<T, A> = T & {
    _annotations: A;
};
export declare type ReadonlyEndpointKeys = "query" | "detail" | "bulkDelete" | "delete" | "count";
export declare type AnnotateEndpointReturn<T extends HasId, Annotation> = Pick<SessionRestEndpoint<WithAnnotations<T, Annotation>>, ReadonlyEndpointKeys>;
export declare type ReadonlySessionRestEndpoint<T extends HasId> = Pick<SessionRestEndpoint<T>, ReadonlyEndpointKeys>;
/**
 * Annotates the data returned by an endpoint with additional data.
 *
 * @param endpoint the restEndpoint to annotate
 * @param addAnnotations a function that takes an array of items and returns an array of the same length with annotations added
 * @returns a new endpoint that returns the same data as the original endpoint, but with annotations added
 */
export declare function annotateEndpoint<T extends HasId, Annotation>(endpoint: SessionRestEndpoint<T>, addAnnotations: (originalItems: T[]) => Promise<WithAnnotations<T, Annotation>[]>): AnnotateEndpointReturn<T, Annotation>;
