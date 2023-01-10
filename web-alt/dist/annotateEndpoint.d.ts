import { HasId, SessionRestEndpoint } from "sessionRest";
export declare type WithAnnotations<T, A> = T & {
    annotations: A;
};
/**
 * Annotates the data returned by an endpoint with additional data.
 *
 * @param endpoint the restEndpoint to annotate
 * @param addAnnotations a function that takes an array of items and returns an array of the same length with annotations added
 * @returns a new endpoint that returns the same data as the original endpoint, but with annotations added
 */
export declare function annotateEndpoint<T extends HasId, Annotation>(endpoint: SessionRestEndpoint<T>, addAnnotations: (originalItems: T[]) => Promise<WithAnnotations<T, Annotation>[]>): SessionRestEndpoint<WithAnnotations<T, Annotation>>;
