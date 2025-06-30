import { Fetcher, HeaderCalculator, Method } from "./fetchingTypes";
export declare class BulkFetcher {
    private serverUrl;
    private headerCalculator;
    private responseInterceptor;
    private notBatchable;
    private delayMs;
    private fetchQueue;
    private scheduled;
    constructor(serverUrl: string, headerCalculator: HeaderCalculator, responseInterceptor: (response: Response) => Response, notBatchable: (path: string, method: Method, body?: any) => boolean, delayMs: number);
    fetch: <I, O>(path: string, method: Method, body?: I | undefined) => Promise<O>;
    private executeFetch;
}
declare type LSError = {
    http: number;
    message?: string;
};
export declare class LsErrorException extends Error {
    httpCode: number;
    error: LSError;
    constructor(httpCode: number, error: LSError);
}
/**
 * Parameters for creating a bulk fetcher.
 */
export declare type CreateBulkFetcherParams = {
    /**
     * The base URL of the server to send requests to. For bulk requests,
     * a POST request will be made to `${serverUrl}/meta/bulk`
     */
    serverUrl: string;
    /**
     * Optional function to compute headers for each request.
     * Defaults to returning an empty object.
     */
    headerCalculator?: HeaderCalculator;
    /**
     * Optional function to intercept and modify responses.
     * Called on each `Response` before it's returned.
     * Defaults to the identity function.
     */
    responseInterceptors?: (response: Response) => Response;
    /**
     * Optional delay in milliseconds before sending a batch.
     * Defaults to 100ms.
     */
    delayMs?: number;
    /**
     * Optional function to determine if a request should not be batched.
     * If this returns true, the request will be sent individually.
     * Defaults to a function that always returns false.
     *
     * @param path - The request path (e.g., `/api/foo`)
     * @param method - The HTTP method (e.g., `GET`, `POST`)
     * @param body - The request body (if any)
     * @returns Whether the request should not be batched
     */
    notBatchable?: (path: string, method: Method, body?: any) => boolean;
};
/**
 * Creates a bulk fetcher instance that batches HTTP requests for efficiency.
 *
 * @param params - Configuration options for the bulk fetcher.
 * @returns A `Fetcher` function that behaves like `fetch`, but supports batching.
 */
export declare function createBulkFetcher(params: CreateBulkFetcherParams): Fetcher;
export {};
