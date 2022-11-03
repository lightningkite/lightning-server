"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.mockRestEndpointFunctions = void 0;
const Condition_1 = require("./Condition");
const Modification_1 = require("./Modification");
const otherModels_1 = require("./otherModels");
function mockRestEndpointFunctions(items, label) {
    return {
        query(requesterToken, input) {
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
        detail(requesterToken, id) {
            const result = items.find((item) => item._id === id);
            console.info(label, "detail", { id, result });
            return new Promise((resolve, reject) => {
                if (result)
                    resolve(result);
                else
                    reject();
            });
        },
        insertBulk(requesterToken, input) {
            input.forEach((item) => items.push(item));
            return Promise.resolve(input);
        },
        insert(requesterToken, input) {
            items.push(input);
            return Promise.resolve(input);
        },
        upsert(requesterToken, id, input) {
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
        bulkReplace(requesterToken, input) {
            console.info(label, "bulkReplace", { input });
            input.forEach((item) => this.replace(requesterToken, item._id, item));
            return Promise.resolve(input);
        },
        replace(requesterToken, id, input) {
            console.info(label, "replace", { id, input });
            const existingItemIndex = items.findIndex((item) => item._id === id);
            if (existingItemIndex >= 0) {
                items[existingItemIndex] = input;
                return Promise.resolve(input);
            }
            return Promise.reject();
        },
        bulkModify(requesterToken, input) {
            console.info(label, "bulkModify", { input });
            const filteredItems = items.filter((item) => (0, Condition_1.evaluateCondition)(input.condition, item));
            return Promise.resolve(filteredItems.length);
        },
        modifyWithDiff(requesterToken, id, input) {
            return Promise.resolve({});
        },
        modify(requesterToken, id, input) {
            console.info(label, "modify", { id, input });
            const existingItemIndex = items.findIndex((item) => item._id === id);
            if (existingItemIndex < 0)
                return Promise.reject();
            const newItem = (0, Modification_1.evaluateModification)(input, items[existingItemIndex]);
            items[existingItemIndex] = newItem;
            return Promise.resolve({});
        },
        bulkDelete(requesterToken, input) {
            console.info(label, "bulkDelete", { input });
            if (!items)
                return Promise.reject();
            const previousLength = items.length;
            items = items.filter((item) => !(0, Condition_1.evaluateCondition)(input, item));
            return Promise.resolve(previousLength - items.length);
        },
        delete(requesterToken, id) {
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
        count(requesterToken, input) {
            console.info(label, "count", { input });
            return this.query(requesterToken, { condition: input }).then((it) => it.length);
        },
        groupCount(requesterToken, input) {
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
        aggregate(requesterToken, input) {
            const { condition, aggregate, property } = input;
            const filteredItems = condition
                ? items.filter((item) => (0, Condition_1.evaluateCondition)(condition, item))
                : items;
            const result = performAggregate(filteredItems.map((item) => Number(item[property])), aggregate);
            console.info(label, "aggregate", { input, result });
            return Promise.resolve(result);
        },
        groupAggregate(requesterToken, input) {
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