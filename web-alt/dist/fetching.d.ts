export declare type Method = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
export declare type HeaderCalculator = () => Promise<Record<string, string>> | Record<string, string>;
export declare type Fetcher = <Body, T>(path: string, method: Method, body?: Body) => Promise<T>;
export declare function createBasicFetcher(baseUrl: string, additionalHeaders?: HeaderCalculator, responseInterceptors?: (x: Response) => Response): Fetcher;
