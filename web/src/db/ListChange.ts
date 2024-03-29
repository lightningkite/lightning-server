// Package: com.lightningkite.lightningdb
// Generated by Khrysalis - this file will be overwritten.
import { ReifiedType, setUpDataClass } from '@lightningkite/khrysalis-runtime'

//! Declares com.lightningkite.lightningdb.ListChange
export class ListChange<T extends any> {
    public constructor(public readonly wholeList: (Array<T> | null) = null, public readonly old: (T | null) = null, public readonly _new: (T | null) = null) {
    }
    public static properties = ["wholeList", "old", "_new"]
    public static propertiesJsonOverride = {_new: "new"}
    public static propertyTypes(T: ReifiedType) { return {wholeList: [Array, T], old: T, _new: T} }
    copy: (values: Partial<ListChange<T>>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
}
setUpDataClass(ListChange)
