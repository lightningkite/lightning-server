import { Condition, evaluateCondition } from "./Condition";

export type Modification<T> =
  | { Assign: T }
  | { Chain: Array<Modification<T>> }
  | { IfNotNull: Modification<NonNullable<T>> }
  | ArraySetModification<T>
  | ComparableModification<T>
  | NumberModification<T>
  | StringModification<T>
  | { [P in keyof T]?: Modification<T[P]> };

type ArraySetModification<T> = T extends Array<infer E>
  ?
      | { ListRemove: Condition<E> }
      | { ListAppend: E }
      | { ListDropFirst: true }
      | { ListDropLast: true }
      | { ListRemoveInstances: E }
      | { SetAppend: E }
      | { SetRemove: Condition<E> }
      | { SetDropFirst: true }
      | { SetDropLast: true }
      | { SetRemoveInstances: E }
      | {
          ListPerElement: {
            condition: Condition<E>;
            modification: Modification<E>;
          };
        }
      | {
          SetPerElement: {
            condition: Condition<E>;
            modification: Modification<E>;
          };
        }
  : never;

// Instant, local date, number, string (comparable)
type ComparableModification<T> = T extends string | number
  ? { CoerceAtMost: T } | { CoerceAtLeast: T }
  : never;

// number (Not null)
type NumberModification<T> = T extends number
  ? { Increment: T } | { Multiply: T }
  : never;

type StringModification<T> = T extends string ? { AppendString: T } : never;


export function evaluateModification<T>(
  modification: Modification<T>,
  model: T
): T {
  const keyAndValue = Object.entries(modification).at(0);
  if (!keyAndValue) {
    throw new Error("Single key expected, received none.");
  }
  const [key, value] = keyAndValue;
  switch (key) {
    case "Assign":
      return value as T;
    case "Chain":
      let current = model;
      for (const item of value as Array<Modification<T>>)
        current = evaluateModification(item, current);
      return current;
    case "IfNotNull":
      if (model !== null && model !== undefined) {
        return value as NonNullable<T>;
      }
      return model;
    case "CoerceAtMost":
      if (typeof model === "string" && typeof value == "string") {
        return model > value ? model : (value as T);
      }
      if (typeof model === "number" && typeof value == "number") {
        return Math.min(model, value) as T;
      }
    case "CoerceAtLeast":
      if (typeof model === "string" && typeof value == "string") {
        return model < value ? model : (value as T);
      }
      if (typeof model === "number" && typeof value == "number") {
        return Math.min(model, value) as T;
      }
    case "Increment": {
      const typedValue = value as number;
      const typedModel = model as number;
      return (typedModel + typedValue) as T;
    }
    case "Multiply": {
      const typedValue = value as number;
      const typedModel = model as number;
      return (typedModel * typedValue) as T;
    }
    case "AppendString": {
      const typedValue = value as string;
      const typedModel = model as string;
      return (typedModel + typedValue) as T;
    }
    case "ListAppend": {
      const typedValue = value as Array<any>;
      const typedModel = model as Array<any>;
      return [...typedModel, ...typedValue] as T;
    }
    case "ListRemove": {
      const typedValue = value as Condition<any>;
      const typedModel = model as Array<any>;
      return typedModel.filter(
        (item) => !evaluateCondition(typedValue, item)
      ) as T;
    }
    case "ListRemoveInstances": {
      const typedValue = value as Array<any>;
      const typedModel = model as Array<any>;
      return typedModel.filter((item) => !typedValue.includes(item)) as T;
    }
    case "ListDropFirst": {
      const typedValue = value as boolean;
      const typedModel = model as Array<any>;
      if (typedValue) {
        return typedModel.slice(1) as T;
      }
    }
    case "ListDropLast": {
      const typedModel = model as Array<any>;
      return (typedModel as Array<any>).slice(0, -1) as T;
    }
    case "ListPerElement": {
      const typedValue = value as {
        condition: Condition<any>;
        modification: Modification<any>;
      };
      const typedModel = model as Array<any>;

      typedModel.forEach((item, index) => {
        if (evaluateCondition(typedValue.condition, item)) {
          typedModel[index] = evaluateModification(
            typedValue.modification,
            item
          );
        }
      });
      return model;
    }
    case "SetAppend": {
      return [...(model as Array<any>), ...(value as Array<any>)] as T;
    }
    case "SetRemove": {
      const typedModel = model as Array<any>;
      const typedValue = value as Condition<any>;
      return typedModel.filter(
        (item) => !evaluateCondition(typedValue, item)
      ) as T;
    }
    case "SetRemoveInstances": {
      const typedModel = model as Array<any>;
      const typedValue = value as Array<any>;
      return typedModel.filter((item) => !typedValue.includes(item)) as T;
    }
    case "SetDropFirst":
      throw new Error("SetDropFirst is not supported yet");
    case "SetDropLast":
      throw new Error("SetDropLast is not supported yet");
    case "SetPerElement": {
      const typedValue = value as {
        condition: Condition<any>;
        modification: Modification<any>;
      };
      const typedModel = model as Array<any>;

      typedModel.forEach((item, index) => {
        if (evaluateCondition(typedValue.condition, item)) {
          typedModel[index] = evaluateModification(
            typedValue.modification,
            item
          );
        }
      });
      return model;
    }
    default:
      const copy: any = { ...model };
      copy[key] = evaluateModification(
        value as Modification<any>,
        (model as any)[key]
      );
      return copy;
  }
}
