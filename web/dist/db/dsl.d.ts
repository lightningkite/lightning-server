import { Condition } from './Condition';
import { Modification } from './Modification';
import { Comparable, TProperty1 } from '@lightningkite/khrysalis-runtime';
export declare function startChain<T extends any>(): KeyPath<T, T>;
export declare class KeyPath<From extends any, To extends any> {
    readonly mapCondition: ((a: Condition<To>) => Condition<From>);
    readonly mapModification: ((a: Modification<To>) => Modification<From>);
    readonly getProp: ((a: From) => To);
    readonly setProp: ((a: From, b: To) => From);
    constructor(mapCondition: ((a: Condition<To>) => Condition<From>), mapModification: ((a: Modification<To>) => Modification<From>), getProp: ((a: From) => To), setProp: ((a: From, b: To) => From));
    get<V extends any>(prop: TProperty1<To, V>): KeyPath<From, V>;
    toString(): string;
}
export declare function condition<T extends any>(setup: ((a: KeyPath<T, T>) => Condition<T>)): Condition<T>;
export declare function modification<T extends any>(setup: ((a: KeyPath<T, T>) => Modification<T>)): Modification<T>;
export declare function xKeyPathAlwaysGet<K extends any>(this_: KeyPath<K, K>): Condition<K>;
export declare function xKeyPathNeverGet<K extends any>(this_: KeyPath<K, K>): Condition<K>;
export declare function xKeyPathEq<K extends any, T extends any>(this_: KeyPath<K, T>, value: T): Condition<K>;
export declare function xKeyPathNeq<K extends any, T extends any>(this_: KeyPath<K, T>, value: T): Condition<K>;
export declare function xKeyPathNe<K extends any, T extends any>(this_: KeyPath<K, T>, value: T): Condition<K>;
export declare function xKeyPathInside<K extends any, T extends any>(this_: KeyPath<K, T>, values: Array<T>): Condition<K>;
export declare function xKeyPathNin<K extends any, T extends any>(this_: KeyPath<K, T>, values: Array<T>): Condition<K>;
export declare function xKeyPathNotIn<K extends any, T extends any>(this_: KeyPath<K, T>, values: Array<T>): Condition<K>;
export declare function xKeyPathGt<K extends any, T extends any>(this_: KeyPath<K, T>, value: T): Condition<K>;
export declare function xKeyPathLt<K extends any, T extends any>(this_: KeyPath<K, T>, value: T): Condition<K>;
export declare function xKeyPathGte<K extends any, T extends any>(this_: KeyPath<K, T>, value: T): Condition<K>;
export declare function xKeyPathLte<K extends any, T extends any>(this_: KeyPath<K, T>, value: T): Condition<K>;
export declare function xKeyPathAllClear<K extends any>(this_: KeyPath<K, number>, mask: number): Condition<K>;
export declare function xKeyPathAllSet<K extends any>(this_: KeyPath<K, number>, mask: number): Condition<K>;
export declare function xKeyPathAnyClear<K extends any>(this_: KeyPath<K, number>, mask: number): Condition<K>;
export declare function xKeyPathAnySet<K extends any>(this_: KeyPath<K, number>, mask: number): Condition<K>;
export declare function xKeyPathContains<K extends any>(this_: KeyPath<K, string>, value: string): Condition<K>;
export declare function xKeyPathContainsCased<K extends any>(this_: KeyPath<K, string>, value: string, ignoreCase: boolean): Condition<K>;
export declare function xKeyPathFullTextSearch<K extends any, V extends any>(this_: KeyPath<K, V>, value: string, ignoreCase: boolean): Condition<K>;
export declare function xKeyPathListAll<K extends any, T extends any>(this_: KeyPath<K, Array<T>>, condition: ((a: KeyPath<T, T>) => Condition<T>)): Condition<K>;
export declare function xKeyPathListAny<K extends any, T extends any>(this_: KeyPath<K, Array<T>>, condition: ((a: KeyPath<T, T>) => Condition<T>)): Condition<K>;
export declare function xKeyPathListSizedEqual<K extends any, T extends any>(this_: KeyPath<K, Array<T>>, count: number): Condition<K>;
export declare function xKeyPathSetAll<K extends any, T extends any>(this_: KeyPath<K, Set<T>>, condition: ((a: KeyPath<T, T>) => Condition<T>)): Condition<K>;
export declare function xKeyPathSetAny<K extends any, T extends any>(this_: KeyPath<K, Set<T>>, condition: ((a: KeyPath<T, T>) => Condition<T>)): Condition<K>;
export declare function xKeyPathSetSizedEqual<K extends any, T extends any>(this_: KeyPath<K, Set<T>>, count: number): Condition<K>;
export declare function xKeyPathContainsKey<K extends any, T extends any>(this_: KeyPath<K, Map<string, T>>, key: string): Condition<K>;
export declare function xKeyPathNotNullGet<K extends any, T extends any>(this_: KeyPath<K, (T | null)>): KeyPath<K, T>;
export declare function xKeyPathGet<K extends any, T extends any>(this_: KeyPath<K, Map<string, T>>, key: string): KeyPath<K, T>;
export declare function xKeyPathListAllGet<K extends any, T extends any>(this_: KeyPath<K, Array<T>>): KeyPath<K, T>;
export declare function xKeyPathSetAllGet<K extends any, T extends any>(this_: KeyPath<K, Set<T>>): KeyPath<K, T>;
export declare function xKeyPathListAnyGet<K extends any, T extends any>(this_: KeyPath<K, Array<T>>): KeyPath<K, T>;
export declare function xKeyPathSetAnyGet<K extends any, T extends any>(this_: KeyPath<K, Set<T>>): KeyPath<K, T>;
export declare function xKeyPathCondition<K extends any, T extends any>(this_: KeyPath<K, T>, make: ((a: KeyPath<T, T>) => Condition<T>)): Condition<K>;
export declare function xKeyPathModification<K extends any, T extends any>(this_: KeyPath<K, T>, make: ((a: KeyPath<T, T>) => Modification<T>)): Modification<K>;
export declare function xKeyPathAssign<K extends any, T extends any>(this_: KeyPath<K, T>, value: T): Modification<K>;
export declare function xKeyPathCoerceAtMost<K extends any, T extends Comparable<T>>(this_: KeyPath<K, T>, value: T): Modification<K>;
export declare function xKeyPathCoerceAtLeast<K extends any, T extends Comparable<T>>(this_: KeyPath<K, T>, value: T): Modification<K>;
export declare function xKeyPathPlusNumber<K extends any, T extends number>(this_: KeyPath<K, T>, by: T): Modification<K>;
export declare function xKeyPathTimes<K extends any, T extends number>(this_: KeyPath<K, T>, by: T): Modification<K>;
export declare function xKeyPathPlusString<K extends any>(this_: KeyPath<K, string>, value: string): Modification<K>;
export declare function xKeyPathPlusItemsList<K extends any, T>(this_: KeyPath<K, Array<T>>, items: Array<T>): Modification<K>;
export declare function xKeyPathPlusItemsSet<K extends any, T>(this_: KeyPath<K, Set<T>>, items: Set<T>): Modification<K>;
export declare function xKeyPathPlusItemList<K extends any, T>(this_: KeyPath<K, Array<T>>, item: T): Modification<K>;
export declare function xKeyPathPlusItemSet<K extends any, T>(this_: KeyPath<K, Set<T>>, item: T): Modification<K>;
export declare function xKeyPathListAddAll<K extends any, T extends any>(this_: KeyPath<K, Array<T>>, items: Array<T>): Modification<K>;
export declare function xKeyPathSetAddAll<K extends any, T extends any>(this_: KeyPath<K, Set<T>>, items: Set<T>): Modification<K>;
export declare function xKeyPathListRemove<K extends any, T extends any>(this_: KeyPath<K, Array<T>>, condition: ((a: KeyPath<T, T>) => Condition<T>)): Modification<K>;
export declare function xKeyPathSetRemove<K extends any, T extends any>(this_: KeyPath<K, Set<T>>, condition: ((a: KeyPath<T, T>) => Condition<T>)): Modification<K>;
export declare function xKeyPathListRemoveAll<K extends any, T extends any>(this_: KeyPath<K, Array<T>>, items: Array<T>): Modification<K>;
export declare function xKeyPathSetRemoveAll<K extends any, T extends any>(this_: KeyPath<K, Set<T>>, items: Set<T>): Modification<K>;
export declare function xKeyPathListDropLast<K extends any, T extends any>(this_: KeyPath<K, Array<T>>): Modification<K>;
export declare function xKeyPathSetDropLast<K extends any, T extends any>(this_: KeyPath<K, Set<T>>): Modification<K>;
export declare function xKeyPathListDropFirst<K extends any, T extends any>(this_: KeyPath<K, Array<T>>): Modification<K>;
export declare function xKeyPathSetDropFirst<K extends any, T extends any>(this_: KeyPath<K, Set<T>>): Modification<K>;
export declare function xKeyPathListMap<K extends any, T extends any>(this_: KeyPath<K, Array<T>>, modification: ((a: KeyPath<T, T>) => Modification<T>)): Modification<K>;
export declare function xKeyPathSetMap<K extends any, T extends any>(this_: KeyPath<K, Set<T>>, modification: ((a: KeyPath<T, T>) => Modification<T>)): Modification<K>;
export declare function xKeyPathListMapIf<K extends any, T extends any>(this_: KeyPath<K, Array<T>>, condition: ((a: KeyPath<T, T>) => Condition<T>), modification: ((a: KeyPath<T, T>) => Modification<T>)): Modification<K>;
export declare function xKeyPathSetMapIf<K extends any, T extends any>(this_: KeyPath<K, Set<T>>, condition: ((a: KeyPath<T, T>) => Condition<T>), modification: ((a: KeyPath<T, T>) => Modification<T>)): Modification<K>;
export declare function xKeyPathPlusMap<K extends any, T extends any>(this_: KeyPath<K, Map<string, T>>, map: Map<string, T>): Modification<K>;
export declare function xKeyPathModifyByKey<K extends any, T extends any>(this_: KeyPath<K, Map<string, T>>, map: Map<string, ((a: KeyPath<T, T>) => Modification<T>)>): Modification<K>;
export declare function xKeyPathRemoveKeys<K extends any, T extends any>(this_: KeyPath<K, Map<string, T>>, fields: Set<string>): Modification<K>;
export declare function xUnitThen(this_: void, ignored: void): void;
