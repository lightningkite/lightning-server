import { Fetcher, HeaderCalculator } from "./fetchingTypes";
/**
 * Creates a fetcher for making api calls
 * @param baseUrl Server URL
 * @param additionalHeaders Optional function for computing headers for each request. Defaults to () => ({})
 * @param responseInterceptors Intercepts responses before they are resolved.
 * @returns a 'Fetcher' function for making requests
 */
export declare function createBasicFetcher(baseUrl: string, additionalHeaders?: HeaderCalculator, responseInterceptors?: (x: Response) => Response): Fetcher;
