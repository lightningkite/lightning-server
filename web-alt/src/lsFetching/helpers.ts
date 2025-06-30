import { Query } from "../otherModels";
import { CreateBulkFetcherParams } from "./bulkFetcher";

/**
 *
 * @param maxLimit Highest 'limit' in a query allowed
 * @returns a function for determining whether the query is above the 'maxLimit'
 */
export const excessiveQueryLimitSize =
  (
    maxLimit: Query<any>["limit"] = 100
  ): CreateBulkFetcherParams["notBatchable"] =>
  (_, __, body) => {
    if (typeof body === "object" && body !== null) {
      if ("limit" in body) {
        if (typeof body.limit === "number") {
          return body.limit > 100;
        }
      }
    }
    return false;
  };
