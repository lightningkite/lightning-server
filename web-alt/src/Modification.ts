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
    case "Increment":
      throw new Error("Increment is not supported yet");
    case "Multiply":
      throw new Error("Multiply is not supported yet");
    case "AppendString":
      throw new Error("AppendString is not supported yet");
    case "ListAppend":
      throw new Error("ListAppend is not supported yet");
    case "ListRemove":
      throw new Error("ListRemove is not supported yet");
    case "ListRemoveInstances":
      throw new Error("ListRemoveInstances is not supported yet");
    case "ListDropFirst":
      throw new Error("ListDropFirst is not supported yet");
    case "ListDropLast":
      throw new Error("ListDropLast is not supported yet");
    case "ListPerElement":
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

    case "SetAppend":
      throw new Error("SetAppend is not supported yet");
    case "SetRemove":
      throw new Error("SetRemove is not supported yet");
    case "SetRemoveInstances":
      throw new Error("SetRemoveInstances is not supported yet");
    case "SetDropFirst":
      throw new Error("SetDropFirst is not supported yet");
    case "SetDropLast":
      throw new Error("SetDropLast is not supported yet");
    case "SetPerElement":
      throw new Error("SetPerElement is not supported yet");
    case "Combine":
      throw new Error("Combine is not supported yet");
    case "ModifyByKey":
      throw new Error("ModifyByKey is not supported yet");
    case "RemoveKeys":
      throw new Error("RemoveKeys is not supported yet");
    default:
      const copy: any = { ...model };
      copy[key] = evaluateModification(
        value as Modification<any>,
        (model as any)[key]
      );
      return copy;
  }
}
