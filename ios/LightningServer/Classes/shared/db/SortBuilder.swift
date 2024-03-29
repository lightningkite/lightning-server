// Package: com.lightningkite.lightningdb
// Generated by Khrysalis - this file will be overwritten.
import KhrysalisRuntime
import Foundation

public func sort<T : Codable & Hashable>(setup: @escaping (SortBuilder<T>, DataClassPath<T, T>) -> Void) -> Array<SortPart<T>> {
    return also((SortBuilder() as SortBuilder<T>), { (this) -> Void in setup(this, path()) }).build()
}

public final class SortBuilder<K : Codable & Hashable> {
    public init() {
        self.sortParts = [] as Array<SortPart<K>>
        //Necessary properties should be initialized now
    }
    
    public var sortParts: Array<SortPart<K>>
    public func add(sort: SortPart<K>) -> Void { self.sortParts.append(sort) }
    public func build() -> Array<SortPart<K>> {
        return self.sortParts;
    }
    public func xDataClassPathAscending<V>(_ this: DataClassPath<K, V>) -> SortPart<K> {
        return SortPart(field: this, ascending: true);
    }
    public func xDataClassPathDescending<V>(_ this: DataClassPath<K, V>) -> SortPart<K> {
        return SortPart(field: this, ascending: false);
    }
    public func xDataClassPathAscending(_ this: DataClassPath<K, String>, ignoreCase: Bool) -> SortPart<K> {
        return SortPart(field: this, ascending: true, ignoreCase: ignoreCase);
    }
    public func xDataClassPathDescending(_ this: DataClassPath<K, String>, ignoreCase: Bool) -> SortPart<K> {
        return SortPart(field: this, ascending: false, ignoreCase: ignoreCase);
    }
}
