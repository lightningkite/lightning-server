// Package: com.lightningkite.lightningdb
// Managed here.
import {Comparator, DataClass, hashString, tryCastClass} from '@lightningkite/khrysalis-runtime'
import {TProperty1} from "@lightningkite/khrysalis-runtime";

export function keyGet<K, V>(on: K, key: TProperty1<K, V>): V {
    return on[key] as unknown as V
}

export function keySet<K, V>(on: K, key: TProperty1<K, V>, value: V): K {
    const dict: Record<string, any> = {}
    dict[key] = value
    return (on as unknown as DataClass).copy(dict) as unknown as K
}
