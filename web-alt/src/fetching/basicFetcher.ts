import { Fetcher, HeaderCalculator } from "./fetchingTypes";

/**
 * Creates a fetcher for making api calls
 * @param baseUrl Server URL
 * @param additionalHeaders Optional function for computing headers for each request. Defaults to () => ({})
 * @param responseInterceptors Intercepts responses before they are resolved.
 * @returns a 'Fetcher' function for making requests
 */
export function createBasicFetcher(
  baseUrl: string,
  additionalHeaders: HeaderCalculator = () => ({}),
  responseInterceptors?: (x: Response) => Response
): Fetcher {
  return async function <Body, T>(
    path: string,
    method: string,
    body: Body
  ): Promise<T> {
    return apiCall<Body>(
      `${baseUrl}/${path}`,
      body,
      { method, headers: await additionalHeaders() },
      responseInterceptors
    ).then(async (x) => {
      try {
        return await x.json();
      } catch (e) {
        // When the response is 'void'
        return undefined;
      }
    });
  };
}

function apiCall<T>(
  url: string,
  body: T,
  request: RequestInit,
  responseInterceptors?: (x: Response) => Response
): Promise<Response> {
  return fetch(url, {
    ...request,
    headers: {
      ...request.headers,
      "Content-Type": "application/json",
      Accept: "application/json",
    },
    body: JSON.stringify(body),
  }).then((x) => {
    const response = responseInterceptors?.(x) ?? x;
    if (!response.ok) {
      throw response;
    } else return response;
  });
}
