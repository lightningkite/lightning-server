import { Condition, evaluateCondition } from "./Condition";

export type Modification<T> =
  | { Chain: Array<Modification<T>> }
  | { IfNotNull: Modification<T> }
  | { Assign: T }
  | { CoerceAtMost: T }
  | { CoerceAtLeast: T }
  | { Increment: T }
  | { Multiply: T }
  | { AppendString: T }
  | { ListAppend: T }
  | { ListRemove: Condition<any> }
  | { ListRemoveInstances: T }
  | { ListDropFirst: boolean }
  | { ListDropLast: boolean }
  | {
      ListPerElement: {
        condition: Condition<any>;
        modification: Modification<any>;
      };
    }
  | { SetRemove: Condition<any> }
  | { SetRemoveInstances: T }
  | { SetDropFirst: boolean }
  | { SetDropLast: boolean }
  | {
      SetPerElement: {
        condition: Condition<any>;
        modification: Modification<any>;
      };
    }
  | { SetAppend: T }
  | { Combine: T }
  | { ModifyByKey: Record<string, Modification<any>> }
  | { RemoveKeys: Array<string> }
  | { [P in keyof T]?: Modification<T[P]> };

export function evaluateModification<T>(
  modification: Modification<T>,
  model: T
): T {
  const key = Object.keys(modification)[0];
  const value = (modification as any)[key];
  switch (key) {
    case "Assign":
      return value;
    case "Chain":
      let current = model;
      for (const item of value as Array<Modification<T>>)
        current = evaluateModification(item, current);
      return current;
    case "IfNotNull":
      if (model !== null && model !== undefined) {
        return value;
      }
      return model;
    case "CoerceAtMost":
      throw new Error("CoerceAtMost is not supported yet");
    case "CoerceAtLeast":
      throw new Error("CoerceAtLeast is not supported yet");
    case "Increment": {
      const typedValue = value as number;
      const typedModel = model as unknown as number;
      return (typedModel + typedValue) as unknown as T;
    }
    case "Multiply": {
      const typedValue = value as number;
      const typedModel = model as unknown as number;
      return (typedModel * typedValue) as unknown as T;
    }
    case "AppendString": {
      const typedValue = value as string;
      const typedModel = model as unknown as string;
      return (typedModel + typedValue) as unknown as T;
    }
    case "ListAppend": {
      const typedValue = value as Array<any>;
      const typedModel = model as unknown as Array<any>;
      return [...typedModel, ...typedValue] as unknown as T;
    }
    case "ListRemove": {
      const typedValue = value as Condition<any>;
      const typedModel = model as unknown as Array<any>;
      return typedModel.filter(
        (item) => !evaluateCondition(typedValue, item)
      ) as unknown as T;
    }
    case "ListRemoveInstances": {
      const typedValue = value as Array<any>;
      const typedModel = model as unknown as Array<any>;
      return typedModel.filter((item) => !typedValue.includes(item)) as unknown as T;
    }
    case "ListDropFirst": {
      const typedValue = value as boolean;
      const typedModel = model as unknown as Array<any>;
      if (typedValue) {
        return typedModel.slice(1) as unknown as T;
      }
    }
    case "ListDropLast": {
      const typedValue = value as boolean;
      const typedModel = model as unknown as Array<any>;
      if (typedValue) {
        return typedModel.slice(0, -1) as unknown as T;
      }
    }
    case "ListPerElement": {
      const typedValue = value as {
        condition: Condition<any>;
        modification: Modification<any>;
      };
      const typedModel = model as unknown as Array<any>;

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
      const typedModel = model as unknown as Array<any>;
      const typedValue = value as unknown as Array<any>;
      return [...typedModel, ...typedValue] as unknown as T;
    }
    case "SetRemove": {
      const typedModel = model as unknown as Array<any>;
      const typedValue = value as Condition<any>;
      return typedModel.filter(
        (item) => !evaluateCondition(typedValue, item)
      ) as unknown as T;
    }
    case "SetRemoveInstances": {
      const typedModel = model as unknown as Array<any>;
      const typedValue = value as unknown as Array<any>;
      return typedModel.filter((item) => !typedValue.includes(item)) as unknown as T;
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
      const typedModel = model as unknown as Array<any>;

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
    case "Combine":
      throw new Error("Combine is not supported yet");
    case "ModifyByKey": {
      const typedValue = value as Record<string, Modification<any>>;
      const typedModel = model as unknown as Record<string, any>;
      const copy: any = { ...typedModel };

      Object.keys(typedValue).forEach((key) => {
        copy[key] = evaluateModification(typedValue[key], copy[key]);
      });
      return copy;
    }
    case "RemoveKeys": {
      const typedValue = value as Array<string>;
      const typedModel = model as unknown as Record<string, any>;
      const copy: any = { ...typedModel };

      typedValue.forEach((key) => {
        delete copy[key];
      });
      return copy;
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
