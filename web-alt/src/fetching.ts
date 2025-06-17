export type Method = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

export type HeaderCalculator = () =>
  | Promise<Record<string, string>>
  | Record<string, string>;

export type Fetcher = <Body, T>(
  path: string,
  method: Method,
  body?: Body
) => Promise<T>;

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
    ).then((x) => x.json() as Promise<T>);
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