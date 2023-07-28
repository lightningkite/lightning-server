// Package: com.lightningkite.lightningdb
// Generated by Khrysalis - customized.
import KhrysalisRuntime
import Foundation

public class DataClassPathPartial<K: Codable & Hashable> : Hashable, KEquatable, KHashable, KStringable {
    public init() {
        //Necessary properties should be initialized now
    }
    
    open func getAny(key: K) -> Any? { TODO() }
    open func setAny(key: K, any: Any?) -> K { TODO() }
    public var properties: Array<AnyPropertyIterableProperty> { get { TODO() } }
    open func hashCode() -> Int { TODO() }
    open func toString() -> String { TODO() }
    open func equals(other: Any) -> Bool { TODO() }
}

public class DataClassPath<K : Codable & Hashable, V : Codable & Hashable> : DataClassPathPartial<K> {
    override public init() {
        super.init()
        //Necessary properties should be initialized now
    }
    
    open func get(key: K) -> V? { TODO() }
    open func set(key: K, value: V) -> K { TODO() }
    override open func getAny(key: K) -> Any? {
        return self.get(key: key);
    }
    override open func setAny(key: K, any: Any?) -> K {
        return self.set(key: key, value: any as! V);
    }
    open func mapCondition(_ condition: Condition<V>) -> Condition<K> { TODO() }
    open func mapModification(_ modification: Modification<V>) -> Modification<K> { TODO() }
    
    public func get<V2>(prop: PropertyIterableProperty<V, V2>) -> DataClassPathAccess<K, V, V2> {
        return DataClassPathAccess(first: self, second: prop);
    }
}

private protocol DCPSI {}
public final class DataClassPathSelf<K : Codable & Hashable> : DataClassPath<K, K>, DCPSI {
    override public init() {
        super.init()
        //Necessary properties should be initialized now
    }
    
    override public func get(key: K) -> K? {
        return key;
    }
    override public func set(key: K, value: K) -> K {
        return value;
    }
    override public func toString() -> String {
        return "this";
    }
    override public func hashCode() -> Int {
        return 0;
    }
    override public func equals(other: Any) -> Bool {
        return other is DCPSI;
    }
    override public var properties: Array<AnyPropertyIterableProperty> {
        get { return [] }
    }
    override public func mapCondition(_ condition: Condition<K>) -> Condition<K> {
        return condition;
    }
    override public func mapModification(_ modification: Modification<K>) -> Modification<K> {
        return modification;
    }
}
public final class DataClassPathAccess<K : Codable & Hashable, M : Codable & Hashable, V : Codable & Hashable> : DataClassPath<K, V> {
    public var first: DataClassPath<K, M>
    public var second: PropertyIterableProperty<M, V>
    public init(first: DataClassPath<K, M>, second: PropertyIterableProperty<M, V>) {
        self.first = first
        self.second = second
        super.init()
        //Necessary properties should be initialized now
    }
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(first)
        hasher.combine(second)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? DataClassPathAccess else { return false }
        return self.first == other.first && self.second == other.second
    }
    public var description: String { return "DataClassPathAccess(first=\(String(kotlin: self.first)), second=\(String(kotlin: self.second)))" }
    public func copy(first: DataClassPath<K, M>? = nil, second: PropertyIterableProperty<M, V>? = nil) -> DataClassPathAccess<K, M, V> { return DataClassPathAccess(first: first ?? self.first, second: second ?? self.second) }
    
    override public func get(key: K) -> V? {
        return (self.first.get(key: key)).flatMap { temp76 in ({ (it) -> V in self.second.get(it) })(temp76) };
    }
    override public func set(key: K, value: V) -> K {
        return (self.first.get(key: key)).map { (it) in
            return self.first.set(key: key, value: self.second.set(it, value))
        } ?? key;
    }
    override public func toString() -> String {
        return self.first is DCPSI ? self.second.name : "\(self.first).\(String(kotlin: self.second.name))";
    }
    override public var properties: Array<AnyPropertyIterableProperty> {
        get { return self.first.properties + [self.second] }
    }
    override public func mapCondition(_ condition: Condition<V>) -> Condition<K> {
        return self.first.mapCondition(ConditionOnField(key: self.second, condition: condition));
    }
    override public func mapModification(_ modification: Modification<V>) -> Modification<K> {
        return self.first.mapModification(ModificationOnField(key: self.second, modification: modification));
    }
}
public final class DataClassPathNotNull<K : Codable & Hashable, V : Codable & Hashable> : DataClassPath<K, V> {
    public var wraps: DataClassPath<K, V?>
    public init(wraps: DataClassPath<K, V?>) {
        self.wraps = wraps
        super.init()
        //Necessary properties should be initialized now
    }
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(wraps)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? DataClassPathNotNull else { return false }
        return self.wraps == other.wraps
    }
    public var description: String { return "DataClassPathNotNull(wraps=\(String(kotlin: self.wraps)))" }
    public func copy(wraps: DataClassPath<K, V?>? = nil) -> DataClassPathNotNull<K, V> { return DataClassPathNotNull(wraps: wraps ?? self.wraps) }
    
    override public var properties: Array<AnyPropertyIterableProperty> {
        get { return self.wraps.properties }
    }
    
    override public func get(key: K) -> V? {
        return self.wraps.get(key: key).flatMap { $0 };
    }
    override public func set(key: K, value: V) -> K {
        return self.wraps.set(key: key, value: value);
    }
    override public func toString() -> String {
        return "\(self.wraps)?";
    }
    override public func mapCondition(_ condition: Condition<V>) -> Condition<K> {
        return self.wraps.mapCondition(ConditionIfNotNull(condition));
    }
    override public func mapModification(_ modification: Modification<V>) -> Modification<K> {
        return self.wraps.mapModification(ModificationIfNotNull(modification));
    }
}

public func getDataClassPathNotNull<K : Codable & Hashable, V : Codable & Hashable>(_ this: DataClassPath<K, V?>) -> DataClassPathNotNull<K, V> {
    return DataClassPathNotNull(wraps: this);
}
