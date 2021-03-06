// Package: com.lightningkite.lightningdb
// Generated by Khrysalis - this file will be overwritten.
import { PartialDataClassProperty } from './DataClassProperty'
import { Comparator, ReifiedType, compareBy, setUpDataClass } from '@lightningkite/khrysalis-runtime'

//! Declares com.lightningkite.lightningdb.SortPart
export class SortPart<T extends any> {
    public constructor(public readonly field: PartialDataClassProperty<T>, public readonly ascending: boolean = true) {
    }
    public static properties = ["field", "ascending"]
    public static propertyTypes(T: ReifiedType) { return {field: [String, T], ascending: [Boolean]} }
    copy: (values: Partial<SortPart<T>>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
}
setUpDataClass(SortPart)

//! Declares com.lightningkite.lightningdb.comparator>kotlin.collections.Listcom.lightningkite.lightningdb.SortPartcom.lightningkite.lightningdb.comparator.T
export function xListComparatorGet<T extends any>(this_: Array<SortPart<T>>): (Comparator<T> | null) {
    if (this_.length === 0) { return null }
    return (a: T, b: T): number => {
        for (const part of this_) {
            const result = compareBy(part.field as keyof T)!!(a, b);
            if (!(result === 0)) { return part.ascending ? result : (-result) }
        }
        return 0;
    };
}
