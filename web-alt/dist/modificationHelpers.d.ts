import { Modification } from "Modification";
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
export declare function makeObjectModification<T>(oldObject: T, newObject: Partial<T>): Modification<T> | null;
/**
 * Recursively compares two javascript values, including nested object properties and arrays.
 */
export declare const areValuesSame: (a: unknown, b: unknown) => boolean;
/**
 *
 * @param array1
 * @param array2
 * @param areSame (optional) a function that compares two values of the array's type. Defaults to ===.
 * @returns true if the arrays are the same length and contain the same values in the same order.
 */
export declare const areArraysSame: <T>(array1: T[], array2: T[], areSame?: (item1: T, item2: T) => boolean) => boolean;
/**
 *
 * @param object1
 * @param object2
 * @param areSame (optional) a function that compares two values of the object's type. Defaults to ===.
 * @returns true if the objects have the same keys and values.
 */
export declare const areObjectsSame: (object1: Record<string, unknown>, object2: Record<string, unknown>, areSame?: (item1: unknown, item2: unknown) => boolean) => boolean;
