// Package: com.lightningkite.lightningdb
// Generated by Khrysalis - this file will be overwritten.
import KhrysalisRuntime
import Foundation

public func modification<T : Codable & Hashable>(setup: @escaping (ModificationBuilder<T>, DataClassPath<T, T>) -> Void) -> Modification<T> {
    return also((ModificationBuilder() as ModificationBuilder<T>), { (this) -> Void in setup(this, path()) }).build()
}

public extension Modification where T : Codable & Hashable {
    func and(setup: @escaping (ModificationBuilder<T>, DataClassPath<T, T>) -> Void) -> Modification<T> {
        return also((ModificationBuilder() as ModificationBuilder<T>), { (this) -> Void in
            this.modifications.append(self)
            setup(this, path())
        }).build()
    }
}

public final class ModificationBuilder<K : Codable & Hashable> {
    public init() {
        self.modifications = [] as Array<Modification<K>>
        //Necessary properties should be initialized now
    }
    
    public var modifications: Array<Modification<K>>
    public func add(_ modification: Modification<K>) -> Void { self.modifications.append(modification) }
    public func build() -> Modification<K> {
        if self.modifications.count == 1 { return self.modifications[0] } else { return ModificationChain(modifications: self.modifications) }
    }
    
    public func xCMBuilderAssign<T : Codable & Hashable>(_ this: CMBuilder<K, T>, _ value: T) -> Void {
        self.modifications.append(this.mapModification(ModificationAssign(value)))
    }
    
    public func xCMBuilderCoerceAtMost<T : Comparable>(_ this: CMBuilder<K, T>, _ value: T) -> Void {
        self.modifications.append(this.mapModification(ModificationCoerceAtMost(value)))
    }
    
    public func xCMBuilderCoerceAtLeast<T : Comparable>(_ this: CMBuilder<K, T>, _ value: T) -> Void {
        self.modifications.append(this.mapModification(ModificationCoerceAtLeast(value)))
    }
    
    public func xCMBuilderPlusAssign<T : Number>(_ this: CMBuilder<K, T>, by: T) -> Void {
        self.modifications.append(this.mapModification(ModificationIncrement(by: by)))
    }
    
    public func xCMBuilderTimesAssign<T : Number>(_ this: CMBuilder<K, T>, by: T) -> Void {
        self.modifications.append(this.mapModification(ModificationMultiply(by: by)))
    }
    
    public func xCMBuilderPlusAssign(_ this: CMBuilder<K, String>, _ value: String) -> Void {
        self.modifications.append(this.mapModification(ModificationAppendString(value)))
    }
    
    public func xCMBuilderPlusAssign<T>(_ this: CMBuilder<K, Array<T>>, items: Array<T>) -> Void {
        self.modifications.append(this.mapModification(ModificationListAppend(items: items)))
    }
    
    public func xCMBuilderPlusAssign<T>(_ this: CMBuilder<K, Set<T>>, items: Set<T>) -> Void {
        self.modifications.append(this.mapModification(ModificationSetAppend(items: items)))
    }
    
    public func xCMBuilderPlusAssign<T>(_ this: CMBuilder<K, Array<T>>, item: T) -> Void {
        self.modifications.append(this.mapModification(ModificationListAppend(items: [item])))
    }
    
    public func xCMBuilderPlusAssign<T>(_ this: CMBuilder<K, Set<T>>, item: T) -> Void {
        self.modifications.append(this.mapModification(ModificationSetAppend(items: ([item] as Set<T>))))
    }
    
    public func xCMBuilderAddAll<T : Codable & Hashable>(_ this: CMBuilder<K, Array<T>>, items: Array<T>) -> Void {
        self.modifications.append(this.mapModification(ModificationListAppend(items: items)))
    }
    
    public func xCMBuilderAddAll<T : Codable & Hashable>(_ this: CMBuilder<K, Set<T>>, items: Set<T>) -> Void {
        self.modifications.append(this.mapModification(ModificationSetAppend(items: items)))
    }
    
    public func xCMBuilderRemoveAll<T : Codable & Hashable>(_ this: CMBuilder<K, Array<T>>, condition: @escaping (DataClassPath<T, T>) -> Condition<T>) -> Void {
        self.modifications.append(this.mapModification(ModificationListRemove((condition)((path() as DataClassPath<T, T>)))))
    }
    
    public func xCMBuilderRemoveAll<T : Codable & Hashable>(_ this: CMBuilder<K, Set<T>>, condition: @escaping (DataClassPath<T, T>) -> Condition<T>) -> Void {
        self.modifications.append(this.mapModification(ModificationSetRemove((condition)((path() as DataClassPath<T, T>)))))
    }
    
    public func xCMBuilderRemoveAll<T : Codable & Hashable>(_ this: CMBuilder<K, Array<T>>, items: Array<T>) -> Void {
        self.modifications.append(this.mapModification(ModificationListRemoveInstances(items: items)))
    }
    
    public func xCMBuilderRemoveAll<T : Codable & Hashable>(_ this: CMBuilder<K, Set<T>>, items: Set<T>) -> Void {
        self.modifications.append(this.mapModification(ModificationSetRemoveInstances(items: items)))
    }
    
    public func xCMBuilderDropLast<T : Codable & Hashable>(_ this: CMBuilder<K, Array<T>>) -> Void {
        self.modifications.append(this.mapModification(ModificationListDropLast()))
    }
    
    public func xCMBuilderDropLast<T : Codable & Hashable>(_ this: CMBuilder<K, Set<T>>) -> Void {
        self.modifications.append(this.mapModification(ModificationSetDropLast()))
    }
    
    public func xCMBuilderDropFirst<T : Codable & Hashable>(_ this: CMBuilder<K, Array<T>>) -> Void {
        self.modifications.append(this.mapModification(ModificationListDropFirst()))
    }
    
    public func xCMBuilderDropFirst<T : Codable & Hashable>(_ this: CMBuilder<K, Set<T>>) -> Void {
        self.modifications.append(this.mapModification(ModificationSetDropFirst()))
    }
    
    public func xCMBuilderMap<T : Codable & Hashable>(_ this: CMBuilder<K, Array<T>>, modification: @escaping (ModificationBuilder<T>, DataClassPath<T, T>) -> Void) -> Void {
        let builder = (ModificationBuilder<T>() as ModificationBuilder<T>)
        modification(builder, path())
        self.modifications.append(this.mapModification(
            ModificationListPerElement(condition: ConditionAlways(), modification: builder.build())
        ))
    }
    
    public func xCMBuilderMap<T : Codable & Hashable>(_ this: CMBuilder<K, Set<T>>, modification: @escaping (ModificationBuilder<T>, DataClassPath<T, T>) -> Void) -> Void {
        let builder = (ModificationBuilder<T>() as ModificationBuilder<T>)
        modification(builder, path())
        self.modifications.append(this.mapModification(
            ModificationSetPerElement(condition: ConditionAlways(), modification: builder.build())
        ))
    }
    
    public func xCMBuilderMapIf<T : Codable & Hashable>(_ this: CMBuilder<K, Array<T>>, condition: @escaping (DataClassPath<T, T>) -> Condition<T>, modification: @escaping (ModificationBuilder<T>, DataClassPath<T, T>) -> Void) -> Void {
        let builder = (ModificationBuilder<T>() as ModificationBuilder<T>)
        modification(builder, path())
        self.modifications.append(this.mapModification(
            ModificationListPerElement(condition: (condition)((path() as DataClassPath<T, T>)), modification: builder.build())
        ))
    }
    
    public func xCMBuilderMapIf<T : Codable & Hashable>(_ this: CMBuilder<K, Set<T>>, condition: @escaping (DataClassPath<T, T>) -> Condition<T>, modification: @escaping (ModificationBuilder<T>, DataClassPath<T, T>) -> Void) -> Void {
        let builder = (ModificationBuilder<T>() as ModificationBuilder<T>)
        modification(builder, path())
        self.modifications.append(this.mapModification(
            ModificationSetPerElement(condition: (condition)((path() as DataClassPath<T, T>)), modification: builder.build())
        ))
    }
    
    public func xCMBuilderPlusAssign<T : Codable & Hashable>(_ this: CMBuilder<K, Dictionary<String, T>>, _ map: Dictionary<String, T>) -> Void {
        self.modifications.append(this.mapModification(ModificationCombine(map)))
    }
    
    public func xCMBuilderModifyByKey<T : Codable & Hashable>(_ this: CMBuilder<K, Dictionary<String, T>>, byKey: Dictionary<String, (ModificationBuilder<T>, DataClassPath<T, T>) -> Void>) -> Void {
        self.modifications.append(this.mapModification(ModificationModifyByKey(byKey.mapValuesFromPairs({ (it) -> Modification<T> in modification(setup: it.value) }))))
    }
    
    public func xCMBuilderRemoveKeys<T : Codable & Hashable>(_ this: CMBuilder<K, Dictionary<String, T>>, fields: Set<String>) -> Void {
        self.modifications.append(this.mapModification(ModificationRemoveKeys(fields: fields)))
    }
    
    // ---
    
    public func xDataClassPathAssign<T : Codable & Hashable>(_ this: DataClassPath<K, T>, _ value: T) -> Void {
        self.modifications.append(this.mapModification(ModificationAssign(value)))
    }
    
    public func xDataClassPathCoerceAtMost<T : Comparable>(_ this: DataClassPath<K, T>, _ value: T) -> Void {
        self.modifications.append(this.mapModification(ModificationCoerceAtMost(value)))
    }
    
    public func xDataClassPathCoerceAtLeast<T : Comparable>(_ this: DataClassPath<K, T>, _ value: T) -> Void {
        self.modifications.append(this.mapModification(ModificationCoerceAtLeast(value)))
    }
    
    public func xDataClassPathPlusAssign<T : Number>(_ this: DataClassPath<K, T>, by: T) -> Void {
        self.modifications.append(this.mapModification(ModificationIncrement(by: by)))
    }
    
    public func xDataClassPathTimesAssign<T : Number>(_ this: DataClassPath<K, T>, by: T) -> Void {
        self.modifications.append(this.mapModification(ModificationMultiply(by: by)))
    }
    
    public func xDataClassPathPlusAssign(_ this: DataClassPath<K, String>, _ value: String) -> Void {
        self.modifications.append(this.mapModification(ModificationAppendString(value)))
    }
    
    public func xDataClassPathPlusAssign<T>(_ this: DataClassPath<K, Array<T>>, items: Array<T>) -> Void {
        self.modifications.append(this.mapModification(ModificationListAppend(items: items)))
    }
    
    public func xDataClassPathPlusAssign<T>(_ this: DataClassPath<K, Set<T>>, items: Set<T>) -> Void {
        self.modifications.append(this.mapModification(ModificationSetAppend(items: items)))
    }
    
    public func xDataClassPathPlusAssign<T>(_ this: DataClassPath<K, Array<T>>, item: T) -> Void {
        self.modifications.append(this.mapModification(ModificationListAppend(items: [item])))
    }
    
    public func xDataClassPathPlusAssign<T>(_ this: DataClassPath<K, Set<T>>, item: T) -> Void {
        self.modifications.append(this.mapModification(ModificationSetAppend(items: ([item] as Set<T>))))
    }
    
    public func xDataClassPathAddAll<T : Codable & Hashable>(_ this: DataClassPath<K, Array<T>>, items: Array<T>) -> Void {
        self.modifications.append(this.mapModification(ModificationListAppend(items: items)))
    }
    
    public func xDataClassPathAddAll<T : Codable & Hashable>(_ this: DataClassPath<K, Set<T>>, items: Set<T>) -> Void {
        self.modifications.append(this.mapModification(ModificationSetAppend(items: items)))
    }
    
    public func xDataClassPathRemoveAll<T : Codable & Hashable>(_ this: DataClassPath<K, Array<T>>, condition: @escaping (DataClassPath<T, T>) -> Condition<T>) -> Void {
        self.modifications.append(this.mapModification(ModificationListRemove((condition)((path() as DataClassPath<T, T>)))))
    }
    
    public func xDataClassPathRemoveAll<T : Codable & Hashable>(_ this: DataClassPath<K, Set<T>>, condition: @escaping (DataClassPath<T, T>) -> Condition<T>) -> Void {
        self.modifications.append(this.mapModification(ModificationSetRemove((condition)((path() as DataClassPath<T, T>)))))
    }
    
    public func xDataClassPathRemoveAll<T : Codable & Hashable>(_ this: DataClassPath<K, Array<T>>, items: Array<T>) -> Void {
        self.modifications.append(this.mapModification(ModificationListRemoveInstances(items: items)))
    }
    
    public func xDataClassPathRemoveAll<T : Codable & Hashable>(_ this: DataClassPath<K, Set<T>>, items: Set<T>) -> Void {
        self.modifications.append(this.mapModification(ModificationSetRemoveInstances(items: items)))
    }
    
    public func xDataClassPathDropLast<T : Codable & Hashable>(_ this: DataClassPath<K, Array<T>>) -> Void {
        self.modifications.append(this.mapModification(ModificationListDropLast()))
    }
    
    public func xDataClassPathDropLast<T : Codable & Hashable>(_ this: DataClassPath<K, Set<T>>) -> Void {
        self.modifications.append(this.mapModification(ModificationSetDropLast()))
    }
    
    public func xDataClassPathDropFirst<T : Codable & Hashable>(_ this: DataClassPath<K, Array<T>>) -> Void {
        self.modifications.append(this.mapModification(ModificationListDropFirst()))
    }
    
    public func xDataClassPathDropFirst<T : Codable & Hashable>(_ this: DataClassPath<K, Set<T>>) -> Void {
        self.modifications.append(this.mapModification(ModificationSetDropFirst()))
    }
    
    public func xDataClassPathForEach<T : Codable & Hashable>(_ this: DataClassPath<K, Array<T>>, modification: @escaping (ModificationBuilder<T>, DataClassPath<T, T>) -> Void) -> Void {
        let builder = (ModificationBuilder<T>() as ModificationBuilder<T>)
        modification(builder, path())
        self.modifications.append(this.mapModification(ModificationListPerElement(condition: ConditionAlways(), modification: builder.build())))
    }
    
    public func xDataClassPathForEach<T : Codable & Hashable>(_ this: DataClassPath<K, Set<T>>, modification: @escaping (ModificationBuilder<T>, DataClassPath<T, T>) -> Void) -> Void {
        let builder = (ModificationBuilder<T>() as ModificationBuilder<T>)
        modification(builder, path())
        self.modifications.append(this.mapModification(ModificationSetPerElement(condition: ConditionAlways(), modification: builder.build())))
    }
    
    public func xDataClassPathForEachIf<T : Codable & Hashable>(_ this: DataClassPath<K, Array<T>>, condition: @escaping (DataClassPath<T, T>) -> Condition<T>, modification: @escaping (ModificationBuilder<T>, DataClassPath<T, T>) -> Void) -> Void {
        let builder = (ModificationBuilder<T>() as ModificationBuilder<T>)
        modification(builder, path())
        self.modifications.append(this.mapModification(ModificationListPerElement(condition: (condition)((path() as DataClassPath<T, T>)), modification: builder.build())))
    }
    
    public func xDataClassPathForEachIf<T : Codable & Hashable>(_ this: DataClassPath<K, Set<T>>, condition: @escaping (DataClassPath<T, T>) -> Condition<T>, modification: @escaping (ModificationBuilder<T>, DataClassPath<T, T>) -> Void) -> Void {
        let builder = (ModificationBuilder<T>() as ModificationBuilder<T>)
        modification(builder, path())
        self.modifications.append(this.mapModification(ModificationSetPerElement(condition: (condition)((path() as DataClassPath<T, T>)), modification: builder.build())))
    }
    
    public func xDataClassPathPlusAssign<T : Codable & Hashable>(_ this: DataClassPath<K, Dictionary<String, T>>, _ map: Dictionary<String, T>) -> Void {
        self.modifications.append(this.mapModification(ModificationCombine(map)))
    }
    
    public func xDataClassPathModifyByKey<T : Codable & Hashable>(_ this: DataClassPath<K, Dictionary<String, T>>, byKey: Dictionary<String, (ModificationBuilder<T>, DataClassPath<T, T>) -> Void>) -> Void {
        self.modifications.append(this.mapModification(ModificationModifyByKey(byKey.mapValuesFromPairs({ (it) -> Modification<T> in modification(setup: it.value) }))))
    }
    
    public func xDataClassPathRemoveKeys<T : Codable & Hashable>(_ this: DataClassPath<K, Dictionary<String, T>>, fields: Set<String>) -> Void {
        self.modifications.append(this.mapModification(ModificationRemoveKeys(fields: fields)))
    }
}
