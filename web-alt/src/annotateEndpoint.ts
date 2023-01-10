import { Condition } from "Condition";
import { Modification } from "Modification";
import {
  AggregateQuery,
  EntryChange,
  GroupCountQuery,
  MassModification,
  Query,
} from "otherModels";
import { HasId, SessionRestEndpoint } from "sessionRest";

export type WithAnnotations<T, A> = T & { annotations: A };

/**
 * Annotates the data returned by an endpoint with additional data.
 *
 * @param endpoint the restEndpoint to annotate
 * @param addAnnotations a function that takes an array of items and returns an array of the same length with annotations added
 * @returns a new endpoint that returns the same data as the original endpoint, but with annotations added
 */
export function annotateEndpoint<T extends HasId, Annotation>(
  endpoint: SessionRestEndpoint<T>,
  addAnnotations: (
    originalItems: T[]
  ) => Promise<WithAnnotations<T, Annotation>[]>
): SessionRestEndpoint<WithAnnotations<T, Annotation>> {
  return {
    async default(): Promise<WithAnnotations<T, Annotation>> {
      const item = await endpoint.default();
      return (await addAnnotations([item]))[0];
    },

    async query(
      input: Query<WithAnnotations<T, Annotation>>
    ): Promise<Array<WithAnnotations<T, Annotation>>> {
      return endpoint.query(input as Query<T>).then(addAnnotations);
    },

    async detail(id: string): Promise<WithAnnotations<T, Annotation>> {
      const item = await endpoint.detail(id);
      return (await addAnnotations([item]))[0];
    },

    async insertBulk(
      input: Array<WithAnnotations<T, Annotation>>
    ): Promise<Array<WithAnnotations<T, Annotation>>> {
      const items = await endpoint.insertBulk(stripAnnotationsArray(input));
      return await addAnnotations(items);
    },

    async insert(
      input: WithAnnotations<T, Annotation>
    ): Promise<WithAnnotations<T, Annotation>> {
      const item = await endpoint.insert(stripAnnotations(input));
      return (await addAnnotations([item]))[0];
    },

    async upsert(
      id: string,
      input: T
    ): Promise<WithAnnotations<T, Annotation>> {
      const item = await endpoint.upsert(id, input);
      return (await addAnnotations([item]))[0];
    },

    async bulkReplace(
      input: Array<WithAnnotations<T, Annotation>>
    ): Promise<Array<WithAnnotations<T, Annotation>>> {
      const items = await endpoint.bulkReplace(stripAnnotationsArray(input));
      return await addAnnotations(items);
    },

    async replace(
      id: string,
      input: T
    ): Promise<WithAnnotations<T, Annotation>> {
      const item = await endpoint.replace(id, input);
      return (await addAnnotations([item]))[0];
    },

    async bulkModify(
      input: MassModification<WithAnnotations<T, Annotation>>
    ): Promise<number> {
      return endpoint.bulkModify(input as MassModification<T>);
    },

    async modifyWithDiff(
      id: string,
      input: Modification<WithAnnotations<T, Annotation>>
    ): Promise<EntryChange<WithAnnotations<T, Annotation>>> {
      return Promise.reject("Not implemented with annotations");
    },

    async modify(
      id: string,
      input: Modification<WithAnnotations<T, Annotation>>
    ): Promise<WithAnnotations<T, Annotation>> {
      const item = await endpoint.modify(id, input as Modification<T>);
      return (await addAnnotations([item]))[0];
    },

    async bulkDelete(
      input: Condition<WithAnnotations<T, Annotation>>
    ): Promise<number> {
      return endpoint.bulkDelete(input as Condition<T>);
    },

    async delete(id: string): Promise<void> {
      return endpoint.delete(id);
    },

    async count(
      input: Condition<WithAnnotations<T, Annotation>>
    ): Promise<number> {
      return endpoint.count(input);
    },

    async groupCount(
      input: GroupCountQuery<WithAnnotations<T, Annotation>>
    ): Promise<Record<string, number>> {
      return endpoint.groupCount(input as GroupCountQuery<T>);
    },

    async aggregate(
      input: AggregateQuery<WithAnnotations<T, Annotation>>
    ): Promise<number | null | undefined> {
      return endpoint.aggregate(input as AggregateQuery<T>);
    },

    async groupAggregate(
      input: AggregateQuery<WithAnnotations<T, Annotation>>
    ): Promise<Record<string, number | null | undefined>> {
      return endpoint.groupAggregate(input as AggregateQuery<T>);
    },
  };
}

function stripAnnotationsArray<T extends HasId>(
  items: WithAnnotations<HasId, unknown>[]
): T[] {
  return items.map((item) => {
    const { annotations, ...rest } = item;
    return rest;
  }) as T[];
}

function stripAnnotations<T extends HasId>(
  item: WithAnnotations<T, unknown>
): T {
  const { annotations, ...rest } = item;
  return rest as unknown as T;
}
