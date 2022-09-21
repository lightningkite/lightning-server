// Package: com.lightningkite.lightningdb
// Generated by Khrysalis - this file will be overwritten.
import KhrysalisRuntime
import Foundation

public func startChain<T : Codable & Hashable>() -> PropChain<T, T> {
    return PropChain(mapCondition: { (it) -> Condition<T> in it }, mapModification: { (it) -> Modification<T> in it }, getProp: { (it) -> T in it }, setProp: { (_, it) -> T in it });
}
public final class PropChain<From : Codable & Hashable, To : Codable & Hashable> : KStringable {
    public var mapCondition: (Condition<To>) -> Condition<From>
    public var mapModification: (Modification<To>) -> Modification<From>
    public var getProp: (From) -> To
    public var setProp: (From, To) -> From
    public init(mapCondition: @escaping (Condition<To>) -> Condition<From>, mapModification: @escaping (Modification<To>) -> Modification<From>, getProp: @escaping (From) -> To, setProp: @escaping (From, To) -> From) {
        self.mapCondition = mapCondition
        self.mapModification = mapModification
        self.getProp = getProp
        self.setProp = setProp
        //Necessary properties should be initialized now
    }
    
    public func get<V : Codable & Hashable>(prop: PropertyIterableProperty<To, V>) -> PropChain<From, V> {
        return PropChain<From, V>(mapCondition: { (it) -> Condition<From> in self.mapCondition(ConditionOnField(key: prop, condition: it)) }, mapModification: { (it) -> Modification<From> in self.mapModification(ModificationOnField(key: prop, modification: it)) }, getProp: { (it) -> V in prop.get(self.getProp(it)) }, setProp: { (from, to) -> From in self.setProp(from, prop.set(self.getProp(from), to)) });
    }
    
    //    override fun hashCode(): Int = mapCondition(Condition.Always()).hashCode()
    
    public func toString() -> String {
        return "PropChain(\(self.mapCondition(ConditionAlways())))";
    }
    
    //    @Suppress("UNCHECKED_CAST")
    //    override fun equals(other: Any?): Boolean = other is PropChain<*, *> && mapCondition(Condition.Always()) == (other as PropChain<Any?, Any?>).mapCondition(Condition.Always())
}

public func condition<T : Codable & Hashable>(setup: @escaping (PropChain<T, T>) -> Condition<T>) -> Condition<T> {
    return (setup)((startChain() as PropChain<T, T>));
}

public func modification<T : Codable & Hashable>(setup: @escaping (PropChain<T, T>) -> Modification<T>) -> Modification<T> {
    return (setup)((startChain() as PropChain<T, T>));
}

public extension PropChain where From : Codable & Hashable, To : Codable & Hashable {
    var always: Condition<To> {
        get { return ConditionAlways() }
    }
}
public extension PropChain where From : Codable & Hashable, To : Codable & Hashable {
    var never: Condition<To> {
        get { return ConditionNever() }
    }
}

public extension PropChain where From : Codable & Hashable, To : Codable & Hashable {
    func eq(_ value: To) -> Condition<From> {
        return self.mapCondition(ConditionEqual(value));
    }
}

public extension PropChain where From : Codable & Hashable, To : Codable & Hashable {
    func neq(_ value: To) -> Condition<From> {
        return self.mapCondition(ConditionNotEqual(value));
    }
}

public extension PropChain where From : Codable & Hashable, To : Codable & Hashable {
    func ne(_ value: To) -> Condition<From> {
        return self.mapCondition(ConditionNotEqual(value));
    }
}

public extension PropChain where From : Codable & Hashable, To : Codable & Hashable {
    func inside(values: Array<To>) -> Condition<From> {
        return self.mapCondition(ConditionInside(values: values));
    }
}

public extension PropChain where From : Codable & Hashable, To : Codable & Hashable {
    func nin(values: Array<To>) -> Condition<From> {
        return self.mapCondition(ConditionNotInside(values: values));
    }
}

public extension PropChain where From : Codable & Hashable, To : Codable & Hashable {
    func notIn(values: Array<To>) -> Condition<From> {
        return self.mapCondition(ConditionNotInside(values: values));
    }
}

public extension PropChain where From : Codable & Hashable, To : Codable & Hashable & Comparable {
    func gt(_ value: To) -> Condition<From> {
        return self.mapCondition(ConditionGreaterThan(value));
    }
}

public extension PropChain where From : Codable & Hashable, To : Codable & Hashable & Comparable {
    func lt(_ value: To) -> Condition<From> {
        return self.mapCondition(ConditionLessThan(value));
    }
}

public extension PropChain where From : Codable & Hashable, To : Codable & Hashable & Comparable {
    func gte(_ value: To) -> Condition<From> {
        return self.mapCondition(ConditionGreaterThanOrEqual(value));
    }
}

public extension PropChain where From : Codable & Hashable, To : Codable & Hashable & Comparable {
    func lte(_ value: To) -> Condition<From> {
        return self.mapCondition(ConditionLessThanOrEqual(value));
    }
}

public extension PropChain where From : Codable & Hashable, To == Int {
    func allClear(mask: Int) -> Condition<From> {
        return self.mapCondition(ConditionIntBitsClear(mask: mask));
    }
}
public extension PropChain where From : Codable & Hashable, To == Int {
    func allSet(mask: Int) -> Condition<From> {
        return self.mapCondition(ConditionIntBitsSet(mask: mask));
    }
}
public extension PropChain where From : Codable & Hashable, To == Int {
    func anyClear(mask: Int) -> Condition<From> {
        return self.mapCondition(ConditionIntBitsAnyClear(mask: mask));
    }
}

public extension PropChain where From : Codable & Hashable, To == Int {
    func anySet(mask: Int) -> Condition<From> {
        return self.mapCondition(ConditionIntBitsAnySet(mask: mask));
    }
}
public extension PropChain where From : Codable & Hashable, To == String {
    func contains(_ value: String) -> Condition<From> {
        return self.mapCondition(ConditionStringContains(value, ignoreCase: true));
    }
}

public extension PropChain where From : Codable & Hashable, To == String {
    func contains(_ value: String, ignoreCase: Bool) -> Condition<From> {
        return self.mapCondition(ConditionStringContains(value, ignoreCase: ignoreCase));
    }
}

public extension PropChain where From : Codable & Hashable, To : Codable & Hashable {
    func fullTextSearch(_ value: String, ignoreCase: Bool) -> Condition<From> {
        return self.mapCondition(ConditionFullTextSearch(value, ignoreCase: ignoreCase));
    }
}

public func xPropChainAll<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, Array<T>>, condition: @escaping (PropChain<T, T>) -> Condition<T>) -> Condition<K> {
    return this.mapCondition(ConditionListAllElements((condition)((startChain() as PropChain<T, T>))));
}

public func xPropChainAny<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, Array<T>>, condition: @escaping (PropChain<T, T>) -> Condition<T>) -> Condition<K> {
    return this.mapCondition(ConditionListAnyElements((condition)((startChain() as PropChain<T, T>))));
}

public func xPropChainSizesEquals<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, Array<T>>, count: Int) -> Condition<K> {
    return this.mapCondition(ConditionListSizesEquals(count: count));
}

public func xPropChainAll<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, Set<T>>, condition: @escaping (PropChain<T, T>) -> Condition<T>) -> Condition<K> {
    return this.mapCondition(ConditionSetAllElements((condition)((startChain() as PropChain<T, T>))));
}

public func xPropChainAny<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, Set<T>>, condition: @escaping (PropChain<T, T>) -> Condition<T>) -> Condition<K> {
    return this.mapCondition(ConditionSetAnyElements((condition)((startChain() as PropChain<T, T>))));
}

public func xPropChainSizesEquals<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, Set<T>>, count: Int) -> Condition<K> {
    return this.mapCondition(ConditionSetSizesEquals(count: count));
}

public func xPropChainContainsKey<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, Dictionary<String, T>>, key: String) -> Condition<K> {
    return this.mapCondition(ConditionExists(key: key));
}

public func getPropChainNotNull<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, T?>) -> PropChain<K, T> {
    return (PropChain(mapCondition: { (it) -> Condition<K> in this.mapCondition(ConditionIfNotNull(it)) } as (Condition<T>) -> Condition<K>, mapModification: { (it) -> Modification<K> in this.mapModification(ModificationIfNotNull(it)) } as (Modification<T>) -> Modification<K>, getProp: { (it) -> T in this.getProp(it)! } as (K) -> T, setProp: { (it, value) -> K in this.setProp(it, value) } as (K, T) -> K) as PropChain<K, T>);
}


public func xPropChainGet<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, Dictionary<String, T>>, key: String) -> PropChain<K, T> {
    return (PropChain<K, T>(
        mapCondition: { (it) -> Condition<K> in
            this.mapCondition(ConditionOnKey(key: key, condition: it))
        } as (Condition<T>) -> Condition<K>,
        mapModification: { (it) -> Modification<K> in this.mapModification(ModificationModifyByKey(dictionaryOf(Pair(key, it)))) } as (Modification<T>) -> Modification<K>,
        getProp: { (it) -> T in
            this.getProp(it)[key]!
        } as (K) -> T,
        setProp: { (from, to) -> K in
            this.setProp(from, (this.getProp(from) + dictionaryOf(Pair(key, to))))
        } as (K, T) -> K) as PropChain<K, T>);
}

public func getPropChainAll<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, Array<T>>) -> PropChain<K, T> {
    return (PropChain(
        mapCondition: { (it) -> Condition<K> in this.mapCondition(ConditionListAllElements(it)) } as (Condition<T>) -> Condition<K>,
        mapModification: { (it) -> Modification<K> in this.mapModification(ModificationListPerElement(condition: ConditionAlways(), modification: it)) } as (Modification<T>) -> Modification<K>,
        getProp: { (it) -> T in this.getProp(it).first! } as (K) -> T,
        setProp: { (from, to) -> K in
            this.setProp(from, this.getProp(from) + [to])
        } as (K, T) -> K) as PropChain<K, T>);
}


public func getPropChainAll<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, Set<T>>) -> PropChain<K, T> {
    return (PropChain(
        mapCondition: { (it) -> Condition<K> in
            this.mapCondition(ConditionSetAllElements(it))
        } as (Condition<T>) -> Condition<K>,
        mapModification: { (it) -> Modification<K> in
            this.mapModification(ModificationSetPerElement(condition: ConditionAlways(), modification: it))
        } as (Modification<T>) -> Modification<K>,
        getProp: { (it) -> T in
            this.getProp(it).first()
        } as (K) -> T,
        setProp: { (from, to) -> K in
            var temp = this.getProp(from)
            temp.insert(to)
            return this.setProp(from, temp)
        } as (K, T) -> K) as PropChain<K, T>);
}


public func getPropChainAny<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, Array<T>>) -> PropChain<K, T> {
    return (PropChain(mapCondition: { (it) -> Condition<K> in this.mapCondition(ConditionListAnyElements(it)) } as (Condition<T>) -> Condition<K>, mapModification: { (it) -> Modification<K> in this.mapModification(ModificationListPerElement(condition: ConditionAlways(), modification: it)) } as (Modification<T>) -> Modification<K>, getProp: { (it) -> T in this.getProp(it).first! } as (K) -> T, setProp: { (from, to) -> K in this.setProp(from, (this.getProp(from) + [to])) } as (K, T) -> K) as PropChain<K, T>);
}


public func getPropChainAny<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, Set<T>>) -> PropChain<K, T> {
    return (PropChain(
        mapCondition: { (it) -> Condition<K> in this.mapCondition(ConditionSetAnyElements(it)) } as (Condition<T>) -> Condition<K>,
        mapModification: { (it) -> Modification<K> in this.mapModification(ModificationSetPerElement(condition: ConditionAlways(), modification: it)) } as (Modification<T>) -> Modification<K>,
        getProp: { (it) -> T in this.getProp(it).first() } as (K) -> T,
        setProp: { (from, to) -> K in
            this.setProp(from, this.getProp(from).union([to]))
        } as (K, T) -> K) as PropChain<K, T>);
}


public extension PropChain where From : Codable & Hashable, To : Codable & Hashable {
    func condition(make: @escaping (PropChain<To, To>) -> Condition<To>) -> Condition<From> {
        return self.mapCondition(make((startChain() as PropChain<To, To>)));
    }
}

public extension PropChain where From : Codable & Hashable, To : Codable & Hashable {
    func modification(make: @escaping (PropChain<To, To>) -> Modification<To>) -> Modification<From> {
        return self.mapModification(make((startChain() as PropChain<To, To>)));
    }
}

public extension PropChain where From : Codable & Hashable, To : Codable & Hashable {
    func assign(_ value: To) -> Modification<From> {
        return self.mapModification(ModificationAssign(value));
    }
}

public extension PropChain where From : Codable & Hashable, To : Comparable {
    func coerceAtMost(_ value: To) -> Modification<From> {
        return self.mapModification(ModificationCoerceAtMost(value));
    }
}

public extension PropChain where From : Codable & Hashable, To : Comparable {
    func coerceAtLeast(_ value: To) -> Modification<From> {
        return self.mapModification(ModificationCoerceAtLeast(value));
    }
}

public extension PropChain where From : Codable & Hashable, To : Number {
    func plus(by: To) -> Modification<From> {
        return self.mapModification(ModificationIncrement(by: by));
    }
}

public extension PropChain where From : Codable & Hashable, To : Number {
    func times(by: To) -> Modification<From> {
        return self.mapModification(ModificationMultiply(by: by));
    }
}

public extension PropChain where From : Codable & Hashable, To == String {
    func plus(_ value: String) -> Modification<From> {
        return self.mapModification(ModificationAppendString(value));
    }
}

public func xPropChainPlus<K : Codable & Hashable, T>(_ this: PropChain<K, Array<T>>, items: Array<T>) -> Modification<K> {
    return this.mapModification(ModificationListAppend(items: items));
}

public func xPropChainPlus<K : Codable & Hashable, T>(_ this: PropChain<K, Set<T>>, items: Set<T>) -> Modification<K> {
    return this.mapModification(ModificationSetAppend(items: items));
}

public func xPropChainPlus<K : Codable & Hashable, T>(_ this: PropChain<K, Array<T>>, item: T) -> Modification<K> {
    return this.mapModification(ModificationListAppend(items: [item]));
}

public func xPropChainPlus<K : Codable & Hashable, T>(_ this: PropChain<K, Set<T>>, item: T) -> Modification<K> {
    return this.mapModification(ModificationSetAppend(items: Set([item])));
}

public func xPropChainAddAll<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, Array<T>>, items: Array<T>) -> Modification<K> {
    return this.mapModification(ModificationListAppend(items: items));
}

public func xPropChainAddAll<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, Set<T>>, items: Set<T>) -> Modification<K> {
    return this.mapModification(ModificationSetAppend(items: items));
}

public func xPropChainRemoveAll<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, Array<T>>, condition: @escaping (PropChain<T, T>) -> Condition<T>) -> Modification<K> {
    return this.mapModification(ModificationListRemove((condition)((startChain() as PropChain<T, T>))));
}

public func xPropChainRemoveAll<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, Set<T>>, condition: @escaping (PropChain<T, T>) -> Condition<T>) -> Modification<K> {
    return this.mapModification(ModificationSetRemove((condition)((startChain() as PropChain<T, T>))));
}

public func xPropChainRemoveAll<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, Array<T>>, items: Array<T>) -> Modification<K> {
    return this.mapModification(ModificationListRemoveInstances(items: items));
}

public func xPropChainRemoveAll<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, Set<T>>, items: Set<T>) -> Modification<K> {
    return this.mapModification(ModificationSetRemoveInstances(items: items));
}

public func xPropChainDropLast<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, Array<T>>) -> Modification<K> {
    return this.mapModification(ModificationListDropLast());
}

public func xPropChainDropLast<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, Set<T>>) -> Modification<K> {
    return this.mapModification(ModificationSetDropLast());
}

public func xPropChainDropFirst<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, Array<T>>) -> Modification<K> {
    return this.mapModification(ModificationListDropFirst());
}

public func xPropChainDropFirst<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, Set<T>>) -> Modification<K> {
    return this.mapModification(ModificationSetDropFirst());
}

public func xPropChainMap<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, Array<T>>, modification: @escaping (PropChain<T, T>) -> Modification<T>) -> Modification<K> {
    return this.mapModification(ModificationListPerElement(condition: ConditionAlways(), modification: (modification)((startChain() as PropChain<T, T>))));
}

public func xPropChainMap<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, Set<T>>, modification: @escaping (PropChain<T, T>) -> Modification<T>) -> Modification<K> {
    return this.mapModification(ModificationSetPerElement(condition: ConditionAlways(), modification: (modification)((startChain() as PropChain<T, T>))));
}

public func xPropChainMapIf<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, Array<T>>, condition: @escaping (PropChain<T, T>) -> Condition<T>, modification: @escaping (PropChain<T, T>) -> Modification<T>) -> Modification<K> {
    return this.mapModification(
        ModificationListPerElement(condition: (condition)((startChain() as PropChain<T, T>)), modification: (modification)((startChain() as PropChain<T, T>)))
    );
}

public func xPropChainMapIf<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, Set<T>>, condition: @escaping (PropChain<T, T>) -> Condition<T>, modification: @escaping (PropChain<T, T>) -> Modification<T>) -> Modification<K> {
    return this.mapModification(
        ModificationSetPerElement(condition: (condition)((startChain() as PropChain<T, T>)), modification: (modification)((startChain() as PropChain<T, T>)))
    );
}

public func xPropChainPlus<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, Dictionary<String, T>>, _ map: Dictionary<String, T>) -> Modification<K> {
    return this.mapModification(ModificationCombine(map));
}

public func xPropChainModifyByKey<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, Dictionary<String, T>>, _ map: Dictionary<String, (PropChain<T, T>) -> Modification<T>>) -> Modification<K> {
    return this.mapModification(ModificationModifyByKey(map.mapValuesFromPairs({ (it) -> Modification<T> in (it.value)((startChain() as PropChain<T, T>)) })));
}

public func xPropChainRemoveKeys<K : Codable & Hashable, T : Codable & Hashable>(_ this: PropChain<K, Dictionary<String, T>>, fields: Set<String>) -> Modification<K> {
    return this.mapModification(ModificationRemoveKeys(fields: fields));
}

