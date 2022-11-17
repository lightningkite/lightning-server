import { evaluateCondition, Condition } from "./Condition";
import { Modification, evaluateModification } from "./Modification";
import {
  Query,
  MassModification,
  EntryChange,
  GroupCountQuery,
  AggregateQuery,
  GroupAggregateQuery,
  Aggregate,
} from "./otherModels";
import { HasId } from "./sessionRest";

export function mockRestEndpointFunctions<T extends HasId>(
  items: T[],
  label: string
) {
  return {
    query(input: Query<T>, requesterToken: string): Promise<Array<T>> {
      const { limit, skip = 0, orderBy, condition } = input;

      const filteredItems = condition
        ? items.filter((item) => evaluateCondition(condition, item))
        : items;

      let sortedItems = filteredItems;

      if (orderBy?.length) {
        const sortModel: { key: keyof T; ascending: boolean }[] = orderBy.map(
          (orderItem) => {
            const ascending = !orderItem.toString().startsWith("-");
            const key = (
              ascending ? orderItem : orderItem.toString().substring(1)
            ) as keyof T;
            return { key, ascending };
          }
        );

        sortedItems = filteredItems.sort((a, b) => {
          for (const { key, ascending } of sortModel) {
            const aValue = a[key];
            const bValue = b[key];
            if (aValue < bValue) {
              return ascending ? -1 : 1;
            } else if (aValue > bValue) {
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

    detail(id: string, requesterToken: string): Promise<T> {
      const result = items.find((item) => item._id === id);
      console.info(label, "detail", { id, result });
      return new Promise((resolve, reject) => {
        if (result) resolve(result);
        else reject();
      });
    },

    insertBulk(input: Array<T>, requesterToken: string): Promise<Array<T>> {
      input.forEach((item) => items.push(item));
      console.info(label, "insertBulk", { input });
      return Promise.resolve(input);
    },

    insert(input: T, requesterToken: string): Promise<T> {
      items.push(input);
      console.info(label, "insert", { input });
      return Promise.resolve(input);
    },

    upsert(id: string, input: T, requesterToken: string): Promise<T> {
      console.info(label, "upsert", { id, input });

      const existingItemIndex = items.findIndex((item) => item._id === id);
      if (existingItemIndex >= 0) {
        items[existingItemIndex] = input;
      } else {
        items.push(input);
      }
      return Promise.resolve(input);
    },

    bulkReplace(input: Array<T>, requesterToken: string): Promise<Array<T>> {
      console.info(label, "bulkReplace", { input });

      input.forEach((item) => this.replace(item._id, item, requesterToken));
      return Promise.resolve(input);
    },

    replace(id: string, input: T, requesterToken: string): Promise<T> {
      console.info(label, "replace", { id, input });

      const existingItemIndex = items.findIndex((item) => item._id === id);
      if (existingItemIndex >= 0) {
        items[existingItemIndex] = input;
        return Promise.resolve(input);
      }
      return Promise.reject();
    },

    async bulkModify(
      input: MassModification<T>,
      requesterToken: string
    ): Promise<number> {
      console.info(label, "bulkModify", { input });

      const filteredItems = items.filter((item) =>
        evaluateCondition(input.condition, item)
      );

      return filteredItems.length;
    },

    modifyWithDiff(
      id: string,
      input: Modification<T>,
      requesterToken: string
    ): Promise<EntryChange<T>> {
      return Promise.resolve({});
    },

    modify(
      id: string,
      input: Modification<T>,
      requesterToken: string
    ): Promise<T> {
      console.info(label, "modify", { id, input });

      const existingItemIndex = items.findIndex((item) => item._id === id);
      if (existingItemIndex < 0) return Promise.reject();

      const newItem = evaluateModification(input, items[existingItemIndex]);
      items[existingItemIndex] = newItem;
      return Promise.resolve(newItem);
    },

    bulkDelete(input: Condition<T>, requesterToken: string): Promise<number> {
      console.info(label, "bulkDelete", { input });

      if (!items) return Promise.reject();
      const previousLength = items.length;
      items = items.filter((item) => !evaluateCondition(input, item));
      return Promise.resolve(previousLength - items.length);
    },

    delete(id: string, requesterToken: string): Promise<void> {
      console.info(label, "delete", { id });

      const existingItemIndex = items.findIndex((item) => item._id === id);
      if (existingItemIndex >= 0) {
        items.splice(existingItemIndex, 1);
        return Promise.resolve();
      } else {
        return Promise.reject();
      }
    },

    count(input: Condition<T>, requesterToken: string): Promise<number> {
      console.info(label, "count", { input });

      return this.query({ condition: input }, requesterToken).then(
        (it) => it.length
      );
    },

    groupCount(
      input: GroupCountQuery<T>,
      requesterToken: string
    ): Promise<Record<string, number>> {
      const { condition, groupBy } = input;

      const filteredItems = condition
        ? items.filter((item) => evaluateCondition(condition, item))
        : items;

      const result = filteredItems.reduce((result, item) => {
        const key =
          typeof item[groupBy] === "string"
            ? (item[groupBy] as unknown as string)
            : JSON.stringify(item[groupBy]);
        result[key] = (result[key] || 0) + 1;
        return result;
      }, {} as Record<string, number>);

      console.info(label, "groupCount", { input, result });

      return Promise.resolve(result);
    },

    aggregate(
      input: AggregateQuery<T>,
      requesterToken: string
    ): Promise<number> {
      const { condition, aggregate, property } = input;

      const filteredItems = condition
        ? items.filter((item) => evaluateCondition(condition, item))
        : items;

      const result = performAggregate(
        filteredItems.map((item) => Number(item[property])),
        aggregate
      );

      console.info(label, "aggregate", { input, result });

      return Promise.resolve(result);
    },

    groupAggregate(
      input: GroupAggregateQuery<T>,
      requesterToken: string
    ): Promise<Record<string, number>> {
      const { aggregate, condition, property, groupBy } = input;

      const filteredItems = condition
        ? items.filter((item) => evaluateCondition(condition, item))
        : items;

      const numberArrays = filteredItems.reduce((result, item) => {
        const key =
          typeof item[groupBy] === "string"
            ? (item[groupBy] as unknown as string)
            : JSON.stringify(item[groupBy]);
        result[key] = [...(result[key] || []), Number(item[property])];
        return result;
      }, {} as Record<string, number[]>);

      const result = Object.keys(numberArrays).reduce((result, key) => {
        const array = numberArrays[key];
        result[key] = performAggregate(array, aggregate);
        return result;
      }, {} as Record<string, number>);

      console.info(label, "groupAggregate", { input, result });

      return Promise.resolve(result);
    },
  };
}

function performAggregate(array: number[], aggregate: Aggregate): number {
  switch (aggregate) {
    case Aggregate.Sum:
      return array.reduce((sum, value) => sum + value, 0);
    case Aggregate.Average:
      return array.reduce((sum, value) => sum + value, 0) / array.length;
    default:
      throw new Error(`Not implemented aggregate: ${aggregate}`);
  }
}
