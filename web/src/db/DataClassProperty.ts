// Package: com.lightningkite.lightningdb
// Managed here.
import {Comparator, DataClass, hashString, tryCastClass} from '@lightningkite/khrysalis-runtime'

//! Declares com.lightningkite.lightningdb.PartialDataClassProperty
export type PartialDataClassProperty<T> = keyof T & string
//! Declares com.lightningkite.lightningdb.DataClassProperty
export type DataClassProperty<T, V> = keyof { [ P in keyof T as T[P] extends V ? P : never ] : P } & keyof T & string;

export function keyGet<K, V>(on: K, key: DataClassProperty<K, V>): V {
    return on[key] as unknown as V
}
export function keySet<K, V>(on: K, key: DataClassProperty<K, V>, value: V): K {
    const dict: Record<string, any> = {}
    dict[key] = value
    return (on as unknown as DataClass).copy(dict) as unknown as K
}
