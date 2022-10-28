import { Modification } from "Modification";

// Return types of the javascript "typeof" operator
type typeofTypes =
  | "string"
  | "number"
  | "bigint"
  | "boolean"
  | "symbol"
  | "undefined"
  | "object"
  | "function";

// Javascript values that can safely be compared with the === operator
const canCompareWithEquals: typeofTypes[] = [
  "string",
  "number",
  "bigint",
  "boolean",
  "symbol",
  "undefined",
];

/**
 * @param oldObject the original object
 * @param newObject partial object, representing the values of fields that might have changed
 * @returns a modification to transform oldObject into newObject. If no changes are needed, returns null. Modifications will only affect "root" fields of the object, not nested fields.
 *
 * Example:
 *
 * makeObjectModification( \
 * 	{foo: 1, bar: 2, fizz: 3, buzz: 4},}, \
 * 	{foo: 1, bar: 0, buzz: 0} \
 * )
 *
 * returns: {Chain: [{bar: {Assign: 0}}, {buzz: {Assign: 0}}]}
 */
export function makeObjectModification<T>(
  oldObject: T,
  newObject: Partial<T>
): Modification<T> | null {
  const modifications: Modification<T>[] = [];

  Object.keys(newObject).forEach((k) => {
    const key = k as keyof T;

    const oldValue = oldObject[key];
    let newValue = newObject[key];

    if (newValue === undefined) {
      newValue = null as unknown as T[keyof T];
    }

    if (!areValuesSame(oldValue, newValue)) {
      modifications.push({
        [key]: { Assign: newValue },
      } as Modification<T>);
    }
  });

  return modifications.length > 0 ? { Chain: modifications } : null;
}

/**
 * Recursively compares two javascript values, including nested object properties and arrays.
 */
export const areValuesSame = (a: unknown, b: unknown): boolean => {
  if (typeof a !== typeof b) {
    return false;
  }

  const types = typeof a;

  if (a === b) {
    return true;
  }

  if (canCompareWithEquals.includes(types)) {
    return false;
  }

  if (Array.isArray(a) && Array.isArray(b)) {
    return areArraysSame(a, b, areValuesSame);
  }

  if (types === "object" && a !== null && b !== null) {
    return areObjectsSame(
      a as Record<string, unknown>,
      b as Record<string, unknown>,
      areValuesSame
    );
  }

  return false;
};

/**
 *
 * @param array1
 * @param array2
 * @param areSame (optional) a function that compares two values of the array's type. Defaults to ===.
 * @returns true if the arrays are the same length and contain the same values in the same order.
 */
export const areArraysSame = <T>(
  array1: Array<T>,
  array2: Array<T>,
  areSame: (item1: T, item2: T) => boolean = (a, b) => a === b
): boolean => {
  if (array1.length !== array2.length) {
    return false;
  }

  return array1.every((item1, index) => areSame(item1, array2[index]));
};

/**
 *
 * @param object1
 * @param object2
 * @param areSame (optional) a function that compares two values of the object's type. Defaults to ===.
 * @returns true if the objects have the same keys and values.
 */
export const areObjectsSame = (
  object1: Record<string, unknown>,
  object2: Record<string, unknown>,
  areSame: (item1: unknown, item2: unknown) => boolean = (a, b) => a === b
): boolean => {
  const keys1 = Object.keys(object1);
  const keys2 = Object.keys(object2);

  if (keys1.length !== keys2.length) {
    return false;
  }

  return keys1.every((key) => areSame(object1[key], object2[key]));
};
