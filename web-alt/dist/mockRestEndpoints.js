"use strict";
var __awaiter = (this && this.__awaiter) || function (thisArg, _arguments, P, generator) {
    function adopt(value) { return value instanceof P ? value : new P(function (resolve) { resolve(value); }); }
    return new (P || (P = Promise))(function (resolve, reject) {
        function fulfilled(value) { try { step(generator.next(value)); } catch (e) { reject(e); } }
        function rejected(value) { try { step(generator["throw"](value)); } catch (e) { reject(e); } }
        function step(result) { result.done ? resolve(result.value) : adopt(result.value).then(fulfilled, rejected); }
        step((generator = generator.apply(thisArg, _arguments || [])).next());
    });
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.mockRestEndpointFunctions = void 0;
const Condition_1 = require("./Condition");
const Modification_1 = require("./Modification");
const otherModels_1 = require("./otherModels");
function mockRestEndpointFunctions(items, label) {
    return {
        default(userToken) {
            return Promise.reject(new Error("Not implemented"));
        },
        query(input, userToken) {
            const { limit, skip = 0, orderBy, condition } = input;
            const filteredItems = condition
                ? items.filter((item) => (0, Condition_1.evaluateCondition)(condition, item))
                : items;
            let sortedItems = filteredItems;
            if (orderBy === null || orderBy === void 0 ? void 0 : orderBy.length) {
                const sortModel = orderBy.map((orderItem) => {
                    const ascending = !orderItem.toString().startsWith("-");
                    const key = (ascending ? orderItem : orderItem.toString().substring(1));
                    return { key, ascending };
                });
                sortedItems = filteredItems.sort((a, b) => {
                    for (const { key, ascending } of sortModel) {
                        const aValue = a[key];
                        const bValue = b[key];
                        if (aValue < bValue) {
                            return ascending ? -1 : 1;
                        }
                        else if (aValue > bValue) {
                            return ascending ? 1 : -1;
                        }
                    }
                    return 0;
                });
            }
            const paginatedItems = limit
                ? sortedItems.slice(skip, skip + limit)
                : sortedItems.slice(skip);
            const result = paginatedItems;
            console.info(label, "query", { query: input, result });
            return Promise.resolve(result);
        },
        detail(id, userToken) {
            const result = items.find((item) => item._id === id);
            console.info(label, "detail", { id, result });
            return new Promise((resolve, reject) => {
                if (result)
                    resolve(result);
                else
                    reject();
            });
        },
        insertBulk(input, userToken) {
            input.forEach((item) => items.push(item));
            console.info(label, "insertBulk", { input });
            return Promise.resolve(input);
        },
        insert(input, userToken) {
            items.push(input);
            console.info(label, "insert", { input });
            return Promise.resolve(input);
        },
        upsert(id, input, userToken) {
            console.info(label, "upsert", { id, input });
            const existingItemIndex = items.findIndex((item) => item._id === id);
            if (existingItemIndex >= 0) {
                items[existingItemIndex] = input;
            }
            else {
                items.push(input);
            }
            return Promise.resolve(input);
        },
        bulkReplace(input, userToken) {
            console.info(label, "bulkReplace", { input });
            input.forEach((item) => this.replace(item._id, item, userToken));
            return Promise.resolve(input);
        },
        replace(id, input, userToken) {
            console.info(label, "replace", { id, input });
            const existingItemIndex = items.findIndex((item) => item._id === id);
            if (existingItemIndex >= 0) {
                items[existingItemIndex] = input;
                return Promise.resolve(input);
            }
            return Promise.reject();
        },
        bulkModify(input, userToken) {
            return __awaiter(this, void 0, void 0, function* () {
                console.info(label, "bulkModify", { input });
                const filteredItems = items.filter((item) => (0, Condition_1.evaluateCondition)(input.condition, item));
                return filteredItems.length;
            });
        },
        modifyWithDiff(id, input, userToken) {
            return Promise.resolve({});
        },
        modify(id, input, userToken) {
            console.info(label, "modify", { id, input });
            const existingItemIndex = items.findIndex((item) => item._id === id);
            if (existingItemIndex < 0)
                return Promise.reject();
            const newItem = (0, Modification_1.evaluateModification)(input, items[existingItemIndex]);
            items[existingItemIndex] = newItem;
            return Promise.resolve(newItem);
        },
        bulkDelete(input, userToken) {
            console.info(label, "bulkDelete", { input });
            if (!items)
                return Promise.reject();
            const previousLength = items.length;
            items = items.filter((item) => !(0, Condition_1.evaluateCondition)(input, item));
            return Promise.resolve(previousLength - items.length);
        },
        delete(id, userToken) {
            console.info(label, "delete", { id });
            const existingItemIndex = items.findIndex((item) => item._id === id);
            if (existingItemIndex >= 0) {
                items.splice(existingItemIndex, 1);
                return Promise.resolve();
            }
            else {
                return Promise.reject();
            }
        },
        count(input, userToken) {
            console.info(label, "count", { input });
            return this.query({ condition: input }, userToken).then((it) => it.length);
        },
        groupCount(input, userToken) {
            const { condition, groupBy } = input;
            const filteredItems = condition
                ? items.filter((item) => (0, Condition_1.evaluateCondition)(condition, item))
                : items;
            const result = filteredItems.reduce((result, item) => {
                const key = typeof item[groupBy] === "string"
                    ? item[groupBy]
                    : JSON.stringify(item[groupBy]);
                result[key] = (result[key] || 0) + 1;
                return result;
            }, {});
            console.info(label, "groupCount", { input, result });
            return Promise.resolve(result);
        },
        aggregate(input, userToken) {
            const { condition, aggregate, property } = input;
            const filteredItems = condition
                ? items.filter((item) => (0, Condition_1.evaluateCondition)(condition, item))
                : items;
            const result = performAggregate(filteredItems.map((item) => Number(item[property])), aggregate);
            console.info(label, "aggregate", { input, result });
            return Promise.resolve(result);
        },
        groupAggregate(input, userToken) {
            const { aggregate, condition, property, groupBy } = input;
            const filteredItems = condition
                ? items.filter((item) => (0, Condition_1.evaluateCondition)(condition, item))
                : items;
            const numberArrays = filteredItems.reduce((result, item) => {
                const key = typeof item[groupBy] === "string"
                    ? item[groupBy]
                    : JSON.stringify(item[groupBy]);
                result[key] = [...(result[key] || []), Number(item[property])];
                return result;
            }, {});
            const result = Object.keys(numberArrays).reduce((result, key) => {
                const array = numberArrays[key];
                result[key] = performAggregate(array, aggregate);
                return result;
            }, {});
            console.info(label, "groupAggregate", { input, result });
            return Promise.resolve(result);
        },
    };
}
exports.mockRestEndpointFunctions = mockRestEndpointFunctions;
function performAggregate(array, aggregate) {
    switch (aggregate) {
        case otherModels_1.Aggregate.Sum:
            return array.reduce((sum, value) => sum + value, 0);
        case otherModels_1.Aggregate.Average:
            return array.reduce((sum, value) => sum + value, 0) / array.length;
        default:
            throw new Error(`Not implemented aggregate: ${aggregate}`);
    }
}
//# sourceMappingURL=mockRestEndpoints.js.map