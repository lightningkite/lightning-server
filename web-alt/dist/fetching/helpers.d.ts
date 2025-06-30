import { Query } from "../otherModels";
import { CreateBulkFetcherParams } from "./bulkFetcher";
/**
 *
 * @param maxLimit Highest 'limit' in a query allowed
 * @returns a function for determining whether the query is above the 'maxLimit'
 */
export declare const excessiveQueryLimitSize: (maxLimit?: Query<any>["limit"]) => CreateBulkFetcherParams["notBatchable"];
