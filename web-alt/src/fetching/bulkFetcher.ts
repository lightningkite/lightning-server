import { Fetcher, HeaderCalculator, Method } from "./fetchingTypes";

export class BulkFetcher {
  private fetchQueue = new Map<
    string,
    {
      request: BulkRequest;
      resolve: (response: BulkResponse) => void;
      reject: (error: unknown) => void;
    }
  >();
  private scheduled = false;

  constructor(
    private serverUrl: string,
    private headerCalculator: HeaderCalculator,
    private responseInterceptor: (response: Response) => Response,
    private notBatchable: (path: string, method: Method, body?: any) => boolean,
    private delayMs: number
  ) {}

  fetch = async <I, O>(path: string, method: Method, body?: I): Promise<O> => {
    const id = crypto.randomUUID();
    if (this.notBatchable(path, method, body)) {
      const headers = await this.headerCalculator();
      headers["Content-Type"] = "application/json";

      return fetch(`${this.serverUrl}${path}`, {
        method: method,
        body: JSON.stringify(body),
        headers: headers,
      })
        .then((res) => {
          this.responseInterceptor?.(res);
          return res.json();
        })
        .then((r) => r);
    }

    return new Promise<BulkResponse>((resolve, reject) => {
      this.fetchQueue.set(id, {
        request: {
          path,
          method,
          body: JSON.stringify(body),
        },
        resolve,
        reject,
      });

      if (!this.scheduled) {
        this.scheduled = true;
        setTimeout(() => this.executeFetch(), this.delayMs);
      }
    }).then((res) => {
      if (res.error) {
        throw new LsErrorException(res.error.http, res.error);
      } else if (res.result !== undefined) {
        return JSON.parse(res.result);
      } else {
        return undefined as unknown as O; // for void return types
      }
    });
  };

  private async executeFetch() {
    const batch = new Map(this.fetchQueue);
    this.fetchQueue.clear();
    this.scheduled = false;

    const headers = await this.headerCalculator();
    headers["Content-Type"] = "application/json";
    headers["Accept"] = "application/json";

    const requestBody = JSON.stringify(
      Object.fromEntries(
        [...batch.entries()].map(([id, entry]) => [id, entry.request])
      )
    );

    try {
      const response = await fetch(`${this.serverUrl}/meta/bulk`, {
        method: "POST",
        headers,
        body: requestBody,
      });

      this.responseInterceptor?.(response);

      if (!response.ok) {
        const errorText = await response.text();
        batch.forEach(({ reject }) =>
          reject(new Error(`${response.status}: ${errorText}`))
        );
        return;
      }

      const responses: Record<string, BulkResponse> = await response.json();

      batch.forEach(({ resolve, reject }, id) => {
        const res = responses[id];
        if (res) {
          resolve(res);
        } else {
          reject(new Error(`Bulk key ${id} not found`));
        }
      });
    } catch (e) {
      batch.forEach(({ reject }) => reject(e));
    }
  }
}

type BulkRequest = {
  path: string;
  method: Method;
  body?: string;
};

type BulkResponse = {
  result?: string;
  error?: LSError;
  durationMs?: number;
};

type LSError = {
  http: number;
  message?: string;
};
export class LsErrorException extends Error {
  constructor(public httpCode: number, public error: LSError) {
    super(error.message ?? `HTTP Error ${httpCode}`);
  }
}

/**
 * Parameters for creating a bulk fetcher.
 */
export type CreateBulkFetcherParams = {
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
export function createBulkFetcher(params: CreateBulkFetcherParams): Fetcher {
  const {
    serverUrl,
    headerCalculator = () => ({}),
    responseInterceptors = (r) => r,
    notBatchable = () => false,
    delayMs = 100,
  } = params;

  return new BulkFetcher(
    serverUrl,
    headerCalculator,
    responseInterceptors,
    notBatchable,
    delayMs
  ).fetch;
}
