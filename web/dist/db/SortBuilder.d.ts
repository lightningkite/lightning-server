import { DataClassPath } from './DataClassPath';
import { SortPart } from './SortPart';
export declare function sort<T extends any>(setup: ((a: SortBuilder<T>, b: DataClassPath<T, T>) => void)): Array<SortPart<T>>;
export declare class SortBuilder<K extends any> {
    constructor();
    readonly sortParts: Array<SortPart<K>>;
    add(sort: SortPart<K>): void;
    build(): Array<SortPart<K>>;
    ascending<V>(this_: DataClassPath<K, V>): SortPart<K>;
    descending<V>(this_: DataClassPath<K, V>): SortPart<K>;
    ascendingString(this_: DataClassPath<K, string>, ignoreCase: boolean): SortPart<K>;
    descendingString(this_: DataClassPath<K, string>, ignoreCase: boolean): SortPart<K>;
}
