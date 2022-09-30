// Package: com.lightningkite.lightningdb
// Generated by Khrysalis - this file will be overwritten.
import { Condition } from './Condition'
import { Modification } from './Modification'
import { keySet } from './TProperty1Extensions'
import { Comparable, EqualOverrideSet, TProperty1, reflectiveGet } from '@lightningkite/khrysalis-runtime'
import { first, map as iMap } from 'iter-tools-es'

//! Declares com.lightningkite.lightningdb.startChain
export function startChain<T extends any>(): PropChain<T, T> {
    return new PropChain<T, T>((it: Condition<T>): Condition<T> => (it), (it: Modification<T>): Modification<T> => (it), (it: T): T => (it), (_0: T, it: T): T => (it));
}
//! Declares com.lightningkite.lightningdb.PropChain
export class PropChain<From extends any, To extends any> {
    public constructor(public readonly mapCondition: ((a: Condition<To>) => Condition<From>), public readonly mapModification: ((a: Modification<To>) => Modification<From>), public readonly getProp: ((a: From) => To), public readonly setProp: ((a: From, b: To) => From)) {
    }
    
    public get<V extends any>(prop: TProperty1<To, V>): PropChain<From, V> {
        return new PropChain<From, V>((it: Condition<V>): Condition<From> => (this.mapCondition(new Condition.OnField<To, V>(prop, it))), (it: Modification<V>): Modification<From> => (this.mapModification(new Modification.OnField<To, V>(prop, it))), (it: From): V => (reflectiveGet(this.getProp(it), prop)), (from: From, to: V): From => (this.setProp(from, keySet(this.getProp(from), prop, to))));
    }
    
    //    override fun hashCode(): Int = mapCondition(Condition.Always()).hashCode()
    
    public toString(): string {
        return `PropChain(${this.mapCondition(new Condition.Always<To>())})`;
    }
    
    //    @Suppress("UNCHECKED_CAST")
    //    override fun equals(other: Any?): Boolean = other is PropChain<*, *> && mapCondition(Condition.Always()) == (other as PropChain<Any?, Any?>).mapCondition(Condition.Always())
}

//! Declares com.lightningkite.lightningdb.condition
export function condition<T extends any>(setup: ((a: PropChain<T, T>) => Condition<T>)): Condition<T> {
    return (setup)(startChain<T>());
}

//! Declares com.lightningkite.lightningdb.modification
export function modification<T extends any>(setup: ((a: PropChain<T, T>) => Modification<T>)): Modification<T> {
    return (setup)(startChain<T>());
}

//! Declares com.lightningkite.lightningdb.always>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.always.K, com.lightningkite.lightningdb.always.K
export function xPropChainAlwaysGet<K extends any>(this_: PropChain<K, K>): Condition<K> { return new Condition.Always<K>(); }

//! Declares com.lightningkite.lightningdb.never>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.never.K, com.lightningkite.lightningdb.never.K
export function xPropChainNeverGet<K extends any>(this_: PropChain<K, K>): Condition<K> { return new Condition.Never<K>(); }


//! Declares com.lightningkite.lightningdb.eq>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.eq.K, com.lightningkite.lightningdb.eq.T
export function xPropChainEq<K extends any, T extends any>(this_: PropChain<K, T>, value: T): Condition<K> {
    return this_.mapCondition(new Condition.Equal<T>(value));
}

//! Declares com.lightningkite.lightningdb.neq>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.neq.K, com.lightningkite.lightningdb.neq.T
export function xPropChainNeq<K extends any, T extends any>(this_: PropChain<K, T>, value: T): Condition<K> {
    return this_.mapCondition(new Condition.NotEqual<T>(value));
}

//! Declares com.lightningkite.lightningdb.ne>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.ne.K, com.lightningkite.lightningdb.ne.T
export function xPropChainNe<K extends any, T extends any>(this_: PropChain<K, T>, value: T): Condition<K> {
    return this_.mapCondition(new Condition.NotEqual<T>(value));
}

//! Declares com.lightningkite.lightningdb.inside>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.inside.K, com.lightningkite.lightningdb.inside.T
export function xPropChainInside<K extends any, T extends any>(this_: PropChain<K, T>, values: Array<T>): Condition<K> {
    return this_.mapCondition(new Condition.Inside<T>(values));
}

//! Declares com.lightningkite.lightningdb.nin>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.nin.K, com.lightningkite.lightningdb.nin.T
export function xPropChainNin<K extends any, T extends any>(this_: PropChain<K, T>, values: Array<T>): Condition<K> {
    return this_.mapCondition(new Condition.NotInside<T>(values));
}

//! Declares com.lightningkite.lightningdb.notIn>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.notIn.K, com.lightningkite.lightningdb.notIn.T
export function xPropChainNotIn<K extends any, T extends any>(this_: PropChain<K, T>, values: Array<T>): Condition<K> {
    return this_.mapCondition(new Condition.NotInside<T>(values));
}

//! Declares com.lightningkite.lightningdb.gt>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.gt.K, com.lightningkite.lightningdb.gt.T
export function xPropChainGt<K extends any, T extends any>(this_: PropChain<K, T>, value: T): Condition<K> {
    return this_.mapCondition(new Condition.GreaterThan<T>(value));
}

//! Declares com.lightningkite.lightningdb.lt>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.lt.K, com.lightningkite.lightningdb.lt.T
export function xPropChainLt<K extends any, T extends any>(this_: PropChain<K, T>, value: T): Condition<K> {
    return this_.mapCondition(new Condition.LessThan<T>(value));
}

//! Declares com.lightningkite.lightningdb.gte>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.gte.K, com.lightningkite.lightningdb.gte.T
export function xPropChainGte<K extends any, T extends any>(this_: PropChain<K, T>, value: T): Condition<K> {
    return this_.mapCondition(new Condition.GreaterThanOrEqual<T>(value));
}

//! Declares com.lightningkite.lightningdb.lte>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.lte.K, com.lightningkite.lightningdb.lte.T
export function xPropChainLte<K extends any, T extends any>(this_: PropChain<K, T>, value: T): Condition<K> {
    return this_.mapCondition(new Condition.LessThanOrEqual<T>(value));
}

//! Declares com.lightningkite.lightningdb.allClear>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.allClear.K, kotlin.Int
export function xPropChainAllClear<K extends any>(this_: PropChain<K, number>, mask: number): Condition<K> {
    return this_.mapCondition(new Condition.IntBitsClear(mask));
}
//! Declares com.lightningkite.lightningdb.allSet>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.allSet.K, kotlin.Int
export function xPropChainAllSet<K extends any>(this_: PropChain<K, number>, mask: number): Condition<K> {
    return this_.mapCondition(new Condition.IntBitsSet(mask));
}
//! Declares com.lightningkite.lightningdb.anyClear>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.anyClear.K, kotlin.Int
export function xPropChainAnyClear<K extends any>(this_: PropChain<K, number>, mask: number): Condition<K> {
    return this_.mapCondition(new Condition.IntBitsAnyClear(mask));
}

//! Declares com.lightningkite.lightningdb.anySet>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.anySet.K, kotlin.Int
export function xPropChainAnySet<K extends any>(this_: PropChain<K, number>, mask: number): Condition<K> {
    return this_.mapCondition(new Condition.IntBitsAnySet(mask));
}
//! Declares com.lightningkite.lightningdb.contains>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.contains.K, kotlin.String
export function xPropChainContains<K extends any>(this_: PropChain<K, string>, value: string): Condition<K> {
    return this_.mapCondition(new Condition.StringContains(value, true));
}

//! Declares com.lightningkite.lightningdb.contains>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.contains.K, kotlin.String
export function xPropChainContainsCased<K extends any>(this_: PropChain<K, string>, value: string, ignoreCase: boolean): Condition<K> {
    return this_.mapCondition(new Condition.StringContains(value, ignoreCase));
}

//! Declares com.lightningkite.lightningdb.fullTextSearch>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.fullTextSearch.K, com.lightningkite.lightningdb.fullTextSearch.V
export function xPropChainFullTextSearch<K extends any, V extends any>(this_: PropChain<K, V>, value: string, ignoreCase: boolean): Condition<K> {
    return this_.mapCondition(new Condition.FullTextSearch<V>(value, ignoreCase));
}

//! Declares com.lightningkite.lightningdb.all>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.all.K, kotlin.collections.Listcom.lightningkite.lightningdb.all.T
export function xPropChainListAll<K extends any, T extends any>(this_: PropChain<K, Array<T>>, condition: ((a: PropChain<T, T>) => Condition<T>)): Condition<K> {
    return this_.mapCondition(new Condition.ListAllElements<T>((condition)(startChain<T>())));
}

//! Declares com.lightningkite.lightningdb.any>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.any.K, kotlin.collections.Listcom.lightningkite.lightningdb.any.T
export function xPropChainListAny<K extends any, T extends any>(this_: PropChain<K, Array<T>>, condition: ((a: PropChain<T, T>) => Condition<T>)): Condition<K> {
    return this_.mapCondition(new Condition.ListAnyElements<T>((condition)(startChain<T>())));
}

//! Declares com.lightningkite.lightningdb.sizesEquals>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.sizesEquals.K, kotlin.collections.Listcom.lightningkite.lightningdb.sizesEquals.T
export function xPropChainListSizedEqual<K extends any, T extends any>(this_: PropChain<K, Array<T>>, count: number): Condition<K> {
    return this_.mapCondition(new Condition.ListSizesEquals<T>(count));
}

//! Declares com.lightningkite.lightningdb.all>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.all.K, kotlin.collections.Setcom.lightningkite.lightningdb.all.T
export function xPropChainSetAll<K extends any, T extends any>(this_: PropChain<K, Set<T>>, condition: ((a: PropChain<T, T>) => Condition<T>)): Condition<K> {
    return this_.mapCondition(new Condition.SetAllElements<T>((condition)(startChain<T>())));
}

//! Declares com.lightningkite.lightningdb.any>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.any.K, kotlin.collections.Setcom.lightningkite.lightningdb.any.T
export function xPropChainSetAny<K extends any, T extends any>(this_: PropChain<K, Set<T>>, condition: ((a: PropChain<T, T>) => Condition<T>)): Condition<K> {
    return this_.mapCondition(new Condition.SetAnyElements<T>((condition)(startChain<T>())));
}

//! Declares com.lightningkite.lightningdb.sizesEquals>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.sizesEquals.K, kotlin.collections.Setcom.lightningkite.lightningdb.sizesEquals.T
export function xPropChainSetSizedEqual<K extends any, T extends any>(this_: PropChain<K, Set<T>>, count: number): Condition<K> {
    return this_.mapCondition(new Condition.SetSizesEquals<T>(count));
}

//! Declares com.lightningkite.lightningdb.containsKey>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.containsKey.K, kotlin.collections.Mapkotlin.String, com.lightningkite.lightningdb.containsKey.T
export function xPropChainContainsKey<K extends any, T extends any>(this_: PropChain<K, Map<string, T>>, key: string): Condition<K> {
    return this_.mapCondition(new Condition.Exists<T>(key));
}

//! Declares com.lightningkite.lightningdb.notNull>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.notNull.K, com.lightningkite.lightningdb.notNull.T
export function xPropChainNotNullGet<K extends any, T extends any>(this_: PropChain<K, (T | null)>): PropChain<K, T> { return new PropChain<K, T>((it: Condition<T>): Condition<K> => (this_.mapCondition(new Condition.IfNotNull<T>(it))), (it: Modification<T>): Modification<K> => (this_.mapModification(new Modification.IfNotNull<T>(it))), (it: K): T => (this_.getProp(it)!!), (it: K, value: T): K => (this_.setProp(it, value))); }


//! Declares com.lightningkite.lightningdb.get>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.get.K, kotlin.collections.Mapkotlin.String, com.lightningkite.lightningdb.get.T
export function xPropChainGet<K extends any, T extends any>(this_: PropChain<K, Map<string, T>>, key: string): PropChain<K, T> {
    return new PropChain<K, T>((it: Condition<T>): Condition<K> => (this_.mapCondition(new Condition.OnKey<T>(key, it))), (it: Modification<T>): Modification<K> => (this_.mapModification(new Modification.ModifyByKey<T>(new Map([[key, it]])))), (it: K): T => ((this_.getProp(it).get(key) ?? null)!!), (from: K, to: T): K => (this_.setProp(from, new Map([...this_.getProp(from), [key, to]]))));
}

//! Declares com.lightningkite.lightningdb.all>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.all.K, kotlin.collections.Listcom.lightningkite.lightningdb.all.T
export function xPropChainListAllGet<K extends any, T extends any>(this_: PropChain<K, Array<T>>): PropChain<K, T> { return new PropChain<K, T>((it: Condition<T>): Condition<K> => (this_.mapCondition(new Condition.ListAllElements<T>(it))), (it: Modification<T>): Modification<K> => (this_.mapModification(new Modification.ListPerElement<T>(new Condition.Always<T>(), it))), (it: K): T => (first(this_.getProp(it))!), (from: K, to: T): K => (this_.setProp(from, this_.getProp(from).concat([to])))); }


//! Declares com.lightningkite.lightningdb.all>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.all.K, kotlin.collections.Setcom.lightningkite.lightningdb.all.T
export function xPropChainSetAllGet<K extends any, T extends any>(this_: PropChain<K, Set<T>>): PropChain<K, T> { return new PropChain<K, T>((it: Condition<T>): Condition<K> => (this_.mapCondition(new Condition.SetAllElements<T>(it))), (it: Modification<T>): Modification<K> => (this_.mapModification(new Modification.SetPerElement<T>(new Condition.Always<T>(), it))), (it: K): T => (first(this_.getProp(it))!), (from: K, to: T): K => (this_.setProp(from, new EqualOverrideSet([...this_.getProp(from), to])))); }


//! Declares com.lightningkite.lightningdb.any>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.any.K, kotlin.collections.Listcom.lightningkite.lightningdb.any.T
export function xPropChainListAnyGet<K extends any, T extends any>(this_: PropChain<K, Array<T>>): PropChain<K, T> { return new PropChain<K, T>((it: Condition<T>): Condition<K> => (this_.mapCondition(new Condition.ListAnyElements<T>(it))), (it: Modification<T>): Modification<K> => (this_.mapModification(new Modification.ListPerElement<T>(new Condition.Always<T>(), it))), (it: K): T => (first(this_.getProp(it))!), (from: K, to: T): K => (this_.setProp(from, this_.getProp(from).concat([to])))); }


//! Declares com.lightningkite.lightningdb.any>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.any.K, kotlin.collections.Setcom.lightningkite.lightningdb.any.T
export function xPropChainSetAnyGet<K extends any, T extends any>(this_: PropChain<K, Set<T>>): PropChain<K, T> { return new PropChain<K, T>((it: Condition<T>): Condition<K> => (this_.mapCondition(new Condition.SetAnyElements<T>(it))), (it: Modification<T>): Modification<K> => (this_.mapModification(new Modification.SetPerElement<T>(new Condition.Always<T>(), it))), (it: K): T => (first(this_.getProp(it))!), (from: K, to: T): K => (this_.setProp(from, new EqualOverrideSet([...this_.getProp(from), to])))); }


//! Declares com.lightningkite.lightningdb.condition>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.condition.K, com.lightningkite.lightningdb.condition.T
export function xPropChainCondition<K extends any, T extends any>(this_: PropChain<K, T>, make: ((a: PropChain<T, T>) => Condition<T>)): Condition<K> {
    return this_.mapCondition(make(startChain<T>()));
}

//! Declares com.lightningkite.lightningdb.modification>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.modification.K, com.lightningkite.lightningdb.modification.T
export function xPropChainModification<K extends any, T extends any>(this_: PropChain<K, T>, make: ((a: PropChain<T, T>) => Modification<T>)): Modification<K> {
    return this_.mapModification(make(startChain<T>()));
}

//! Declares com.lightningkite.lightningdb.assign>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.assign.K, com.lightningkite.lightningdb.assign.T
export function xPropChainAssign<K extends any, T extends any>(this_: PropChain<K, T>, value: T): Modification<K> {
    return this_.mapModification(new Modification.Assign<T>(value));
}

//! Declares com.lightningkite.lightningdb.coerceAtMost>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.coerceAtMost.K, com.lightningkite.lightningdb.coerceAtMost.T
export function xPropChainCoerceAtMost<K extends any, T extends Comparable<T>>(this_: PropChain<K, T>, value: T): Modification<K> {
    return this_.mapModification(new Modification.CoerceAtMost<T>(value));
}

//! Declares com.lightningkite.lightningdb.coerceAtLeast>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.coerceAtLeast.K, com.lightningkite.lightningdb.coerceAtLeast.T
export function xPropChainCoerceAtLeast<K extends any, T extends Comparable<T>>(this_: PropChain<K, T>, value: T): Modification<K> {
    return this_.mapModification(new Modification.CoerceAtLeast<T>(value));
}

//! Declares com.lightningkite.lightningdb.plus>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.plus.K, com.lightningkite.lightningdb.plus.T
export function xPropChainPlusNumber<K extends any, T extends number>(this_: PropChain<K, T>, by: T): Modification<K> {
    return this_.mapModification(new Modification.Increment<T>(by));
}

//! Declares com.lightningkite.lightningdb.times>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.times.K, com.lightningkite.lightningdb.times.T
export function xPropChainTimes<K extends any, T extends number>(this_: PropChain<K, T>, by: T): Modification<K> {
    return this_.mapModification(new Modification.Multiply<T>(by));
}

//! Declares com.lightningkite.lightningdb.plus>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.plus.K, kotlin.String
export function xPropChainPlusString<K extends any>(this_: PropChain<K, string>, value: string): Modification<K> {
    return this_.mapModification(new Modification.AppendString(value));
}

//! Declares com.lightningkite.lightningdb.plus>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.plus.K, kotlin.collections.Listcom.lightningkite.lightningdb.plus.T
export function xPropChainPlusItemsList<K extends any, T>(this_: PropChain<K, Array<T>>, items: Array<T>): Modification<K> {
    return this_.mapModification(new Modification.ListAppend<T>(items));
}

//! Declares com.lightningkite.lightningdb.plus>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.plus.K, kotlin.collections.Setcom.lightningkite.lightningdb.plus.T
export function xPropChainPlusItemsSet<K extends any, T>(this_: PropChain<K, Set<T>>, items: Set<T>): Modification<K> {
    return this_.mapModification(new Modification.SetAppend<T>(items));
}

//! Declares com.lightningkite.lightningdb.plus>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.plus.K, kotlin.collections.Listcom.lightningkite.lightningdb.plus.T
export function xPropChainPlusItemList<K extends any, T>(this_: PropChain<K, Array<T>>, item: T): Modification<K> {
    return this_.mapModification(new Modification.ListAppend<T>([item]));
}

//! Declares com.lightningkite.lightningdb.plus>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.plus.K, kotlin.collections.Setcom.lightningkite.lightningdb.plus.T
export function xPropChainPlusItemSet<K extends any, T>(this_: PropChain<K, Set<T>>, item: T): Modification<K> {
    return this_.mapModification(new Modification.SetAppend<T>(new EqualOverrideSet([item])));
}

//! Declares com.lightningkite.lightningdb.addAll>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.addAll.K, kotlin.collections.Listcom.lightningkite.lightningdb.addAll.T
export function xPropChainListAddAll<K extends any, T extends any>(this_: PropChain<K, Array<T>>, items: Array<T>): Modification<K> {
    return this_.mapModification(new Modification.ListAppend<T>(items));
}

//! Declares com.lightningkite.lightningdb.addAll>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.addAll.K, kotlin.collections.Setcom.lightningkite.lightningdb.addAll.T
export function xPropChainSetAddAll<K extends any, T extends any>(this_: PropChain<K, Set<T>>, items: Set<T>): Modification<K> {
    return this_.mapModification(new Modification.SetAppend<T>(items));
}

//! Declares com.lightningkite.lightningdb.removeAll>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.removeAll.K, kotlin.collections.Listcom.lightningkite.lightningdb.removeAll.T
export function xPropChainListRemove<K extends any, T extends any>(this_: PropChain<K, Array<T>>, condition: ((a: PropChain<T, T>) => Condition<T>)): Modification<K> {
    return this_.mapModification(new Modification.ListRemove<T>((condition)(startChain<T>())));
}

//! Declares com.lightningkite.lightningdb.removeAll>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.removeAll.K, kotlin.collections.Setcom.lightningkite.lightningdb.removeAll.T
export function xPropChainSetRemove<K extends any, T extends any>(this_: PropChain<K, Set<T>>, condition: ((a: PropChain<T, T>) => Condition<T>)): Modification<K> {
    return this_.mapModification(new Modification.SetRemove<T>((condition)(startChain<T>())));
}

//! Declares com.lightningkite.lightningdb.removeAll>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.removeAll.K, kotlin.collections.Listcom.lightningkite.lightningdb.removeAll.T
export function xPropChainListRemoveAll<K extends any, T extends any>(this_: PropChain<K, Array<T>>, items: Array<T>): Modification<K> {
    return this_.mapModification(new Modification.ListRemoveInstances<T>(items));
}

//! Declares com.lightningkite.lightningdb.removeAll>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.removeAll.K, kotlin.collections.Setcom.lightningkite.lightningdb.removeAll.T
export function xPropChainSetRemoveAll<K extends any, T extends any>(this_: PropChain<K, Set<T>>, items: Set<T>): Modification<K> {
    return this_.mapModification(new Modification.SetRemoveInstances<T>(items));
}

//! Declares com.lightningkite.lightningdb.dropLast>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.dropLast.K, kotlin.collections.Listcom.lightningkite.lightningdb.dropLast.T
export function xPropChainListDropLast<K extends any, T extends any>(this_: PropChain<K, Array<T>>): Modification<K> {
    return this_.mapModification(new Modification.ListDropLast<T>());
}

//! Declares com.lightningkite.lightningdb.dropLast>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.dropLast.K, kotlin.collections.Setcom.lightningkite.lightningdb.dropLast.T
export function xPropChainSetDropLast<K extends any, T extends any>(this_: PropChain<K, Set<T>>): Modification<K> {
    return this_.mapModification(new Modification.SetDropLast<T>());
}

//! Declares com.lightningkite.lightningdb.dropFirst>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.dropFirst.K, kotlin.collections.Listcom.lightningkite.lightningdb.dropFirst.T
export function xPropChainListDropFirst<K extends any, T extends any>(this_: PropChain<K, Array<T>>): Modification<K> {
    return this_.mapModification(new Modification.ListDropFirst<T>());
}

//! Declares com.lightningkite.lightningdb.dropFirst>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.dropFirst.K, kotlin.collections.Setcom.lightningkite.lightningdb.dropFirst.T
export function xPropChainSetDropFirst<K extends any, T extends any>(this_: PropChain<K, Set<T>>): Modification<K> {
    return this_.mapModification(new Modification.SetDropFirst<T>());
}

//! Declares com.lightningkite.lightningdb.map>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.map.K, kotlin.collections.Listcom.lightningkite.lightningdb.map.T
export function xPropChainListMap<K extends any, T extends any>(this_: PropChain<K, Array<T>>, modification: ((a: PropChain<T, T>) => Modification<T>)): Modification<K> {
    return this_.mapModification(new Modification.ListPerElement<T>(new Condition.Always<T>(), (modification)(startChain<T>())));
}

//! Declares com.lightningkite.lightningdb.map>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.map.K, kotlin.collections.Setcom.lightningkite.lightningdb.map.T
export function xPropChainSetMap<K extends any, T extends any>(this_: PropChain<K, Set<T>>, modification: ((a: PropChain<T, T>) => Modification<T>)): Modification<K> {
    return this_.mapModification(new Modification.SetPerElement<T>(new Condition.Always<T>(), (modification)(startChain<T>())));
}

//! Declares com.lightningkite.lightningdb.mapIf>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.mapIf.K, kotlin.collections.Listcom.lightningkite.lightningdb.mapIf.T
export function xPropChainListMapIf<K extends any, T extends any>(this_: PropChain<K, Array<T>>, condition: ((a: PropChain<T, T>) => Condition<T>), modification: ((a: PropChain<T, T>) => Modification<T>)): Modification<K> {
    return this_.mapModification(
        new Modification.ListPerElement<T>((condition)(startChain<T>()), (modification)(startChain<T>()))
    );
}

//! Declares com.lightningkite.lightningdb.mapIf>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.mapIf.K, kotlin.collections.Setcom.lightningkite.lightningdb.mapIf.T
export function xPropChainSetMapIf<K extends any, T extends any>(this_: PropChain<K, Set<T>>, condition: ((a: PropChain<T, T>) => Condition<T>), modification: ((a: PropChain<T, T>) => Modification<T>)): Modification<K> {
    return this_.mapModification(
        new Modification.SetPerElement<T>((condition)(startChain<T>()), (modification)(startChain<T>()))
    );
}

//! Declares com.lightningkite.lightningdb.plus>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.plus.K, kotlin.collections.Mapkotlin.String, com.lightningkite.lightningdb.plus.T
export function xPropChainPlusMap<K extends any, T extends any>(this_: PropChain<K, Map<string, T>>, map: Map<string, T>): Modification<K> {
    return this_.mapModification(new Modification.Combine<T>(map));
}

//! Declares com.lightningkite.lightningdb.modifyByKey>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.modifyByKey.K, kotlin.collections.Mapkotlin.String, com.lightningkite.lightningdb.modifyByKey.T
export function xPropChainModifyByKey<K extends any, T extends any>(this_: PropChain<K, Map<string, T>>, map: Map<string, ((a: PropChain<T, T>) => Modification<T>)>): Modification<K> {
    return this_.mapModification(new Modification.ModifyByKey<T>(new Map(iMap(x => [x[0], ((it: [string, (a: PropChain<T, T>) => Modification<T>]): Modification<T> => ((it[1])(startChain<T>())))(x)], map.entries()))));
}

//! Declares com.lightningkite.lightningdb.removeKeys>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningdb.removeKeys.K, kotlin.collections.Mapkotlin.String, com.lightningkite.lightningdb.removeKeys.T
export function xPropChainRemoveKeys<K extends any, T extends any>(this_: PropChain<K, Map<string, T>>, fields: Set<string>): Modification<K> {
    return this_.mapModification(new Modification.RemoveKeys<T>(fields));
}
