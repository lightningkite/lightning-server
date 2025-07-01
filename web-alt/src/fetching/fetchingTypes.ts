export type Method = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

export type HeaderCalculator = () =>
  | Promise<Record<string, string>>
  | Record<string, string>;

export type Fetcher = <Body, T>(
  path: string,
  method: Method,
  body?: Body
) => Promise<T>;