import { Condition } from "Condition";
import { Query } from "otherModels";
import { HasId, SessionRestEndpoint } from "sessionRest";

export type WithAnnotations<T, A> = T & { _annotations: A };

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
): Pick<
  SessionRestEndpoint<WithAnnotations<T, Annotation>>,
  "query" | "detail" | "bulkDelete" | "delete" | "count"
> {
  return {
    async query(
      input: Query<WithAnnotations<T, Annotation>>
    ): Promise<Array<WithAnnotations<T, Annotation>>> {
      return endpoint.query(input as Query<T>).then(addAnnotations);
    },

    async detail(id: string): Promise<WithAnnotations<T, Annotation>> {
      const item = await endpoint.detail(id);
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
  };
}
