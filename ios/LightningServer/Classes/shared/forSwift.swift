//
//  forSwift.swift
//  KtorBatteries
//
//  Created by Joseph Ivie on 1/27/22.
//

import Foundation
import RxSwiftPlus
import KhrysalisRuntime

enum DataClassError: Error {
    case NotRegisteredYet
}

public extension PartialKeyPathLike {
    var compare: TypedComparator<Root> {
        return { (a, b) in
            let left = self.getAny(a)
            let right = self.getAny(b)
            switch left {
            case let left as Bool:
                return left.compareToResult(right as! Bool)
            case let left as Int8:
                return left.compareToResult(right as! Int8)
            case let left as Int16:
                return left.compareToResult(right as! Int16)
            case let left as Int32:
                return left.compareToResult(right as! Int32)
            case let left as Int64:
                return left.compareToResult(right as! Int64)
            case let left as Int:
                return left.compareToResult(right as! Int)
            case let left as Float:
                return left.compareToResult(right as! Float)
            case let left as Double:
                return left.compareToResult(right as! Double)
            case let left as String:
                return left.compareToResult(right as! String)
            case let left as UUID:
                return left.compareToResult(right as! UUID)
            default:
                return ComparisonResult.orderedSame
            }
        }
    }
}

public protocol Number: Hashable, Codable {
    static func +(lhs: Self, rhs: Self) -> Self
    static func -(lhs: Self, rhs: Self) -> Self
    static func *(lhs: Self, rhs: Self) -> Self
    static func /(lhs: Self, rhs: Self) -> Self
}

public extension Number {
    func plus(other: Self) -> Self { return self + other }
    func times(other: Self) -> Self { return self + other }
}

fileprivate protocol PropertyIterablePropertyLike {
    func parse(structure: inout KeyedDecodingContainer<ConditionCodingKeys>, key: ConditionCodingKeys) throws -> Any
    func parseModification(structure: inout KeyedDecodingContainer<ModificationCodingKeys>, key: ModificationCodingKeys) throws -> Any
}
extension PropertyIterableProperty where Root: Codable & Hashable, Value: Codable & Hashable {
    fileprivate func parse(structure: inout KeyedDecodingContainer<ConditionCodingKeys>, key: ConditionCodingKeys) throws -> Any {
        return ConditionOnField<Root, Value>(key: self, condition: try structure.decode(Condition<Value>.self, forKey: key)) as Any
    }
    fileprivate func parseModification(structure: inout KeyedDecodingContainer<ModificationCodingKeys>, key: ModificationCodingKeys) throws -> Any {
        return ModificationOnField<Root, Value>(key: self, modification: try structure.decode(Modification<Value>.self, forKey: key)) as Any
    }
}

fileprivate struct ConditionCodingKeys: CodingKey, Hashable {
    let name: String

    static let Never = ConditionCodingKeys(stringValue: "Never")
    static let Always = ConditionCodingKeys(stringValue: "Always")
    static let And = ConditionCodingKeys(stringValue: "And")
    static let Or = ConditionCodingKeys(stringValue: "Or")
    static let Not = ConditionCodingKeys(stringValue: "Not")
    static let Equal = ConditionCodingKeys(stringValue: "Equal")
    static let NotEqual = ConditionCodingKeys(stringValue: "NotEqual")
    static let Inside = ConditionCodingKeys(stringValue: "Inside")
    static let NotInside = ConditionCodingKeys(stringValue: "NotInside")
    static let GreaterThan = ConditionCodingKeys(stringValue: "GreaterThan")
    static let LessThan = ConditionCodingKeys(stringValue: "LessThan")
    static let GreaterThanOrEqual = ConditionCodingKeys(stringValue: "GreaterThanOrEqual")
    static let LessThanOrEqual = ConditionCodingKeys(stringValue: "LessThanOrEqual")
    static let FullTextSearch = ConditionCodingKeys(stringValue: "FullTextSearch")
    static let IntBitsClear = ConditionCodingKeys(stringValue: "IntBitsClear")
    static let IntBitsSet = ConditionCodingKeys(stringValue: "IntBitsSet")
    static let IntBitsAnyClear = ConditionCodingKeys(stringValue: "IntBitsAnyClear")
    static let IntBitsAnySet = ConditionCodingKeys(stringValue: "IntBitsAnySet")
    static let AllElements = ConditionCodingKeys(stringValue: "AllElements")
    static let AnyElements = ConditionCodingKeys(stringValue: "AnyElements")
    static let SizesEquals = ConditionCodingKeys(stringValue: "SizesEquals")
    static let Exists = ConditionCodingKeys(stringValue: "Exists")
    static let OnKey = ConditionCodingKeys(stringValue: "OnKey")
    static let IfNotNull = ConditionCodingKeys(stringValue: "IfNotNull")

    var stringValue: String {
        return self.name
    }

    init(stringValue: String) {
        self.name = stringValue
    }

    var intValue: Int? {
        return nil
    }

    init?(intValue: Int) {
        return nil
    }
}


fileprivate protocol ConditionProtocol {
    func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ConditionCodingKeys>) throws
}

fileprivate protocol ComparableCondition {
    static func conditionGreaterThan(_ value: Any) -> Any
    static func conditionLessThan(_ value: Any) -> Any
    static func conditionGreaterThanOrEqual(_ value: Any) -> Any
    static func conditionLessThanOrEqual(_ value: Any) -> Any
}
extension Condition: ComparableCondition where T: Comparable {
    static func conditionGreaterThan(_ value: Any) -> Any { return ConditionGreaterThan(value as! T) }
    static func conditionLessThan(_ value: Any) -> Any { return ConditionLessThan(value as! T) }
    static func conditionGreaterThanOrEqual(_ value: Any) -> Any { return ConditionGreaterThanOrEqual(value as! T) }
    static func conditionLessThanOrEqual(_ value: Any) -> Any { return ConditionLessThanOrEqual(value as! T) }
}
fileprivate protocol ArrayCondition {
    static func any(structure: inout KeyedDecodingContainer<ConditionCodingKeys>) throws -> Any
    static func all(structure: inout KeyedDecodingContainer<ConditionCodingKeys>) throws -> Any
    static func size(_ value: Int) -> Any
}
extension Condition: ArrayCondition where T: Collection, T.Element: Codable & Hashable {
    fileprivate static func any(structure: inout KeyedDecodingContainer<ConditionCodingKeys>) throws -> Any {
        return ConditionAnyElements(try structure.decode(Condition<T.Element>.self, forKey: .AnyElements))
    }
    fileprivate static func all(structure: inout KeyedDecodingContainer<ConditionCodingKeys>) throws -> Any {
        return ConditionAllElements(try structure.decode(Condition<T.Element>.self, forKey: .AllElements))
    }
    fileprivate static func size(_ value: Int) -> Any { return ConditionSizesEquals<T>(count: value) }
}
protocol DictionaryProtocol {
    associatedtype Key
    associatedtype Value
}
extension Dictionary: DictionaryProtocol {}
fileprivate protocol DictionaryCondition {
    static func exists(_ key: String) -> Any
    static func onKey(structure: inout KeyedDecodingContainer<ConditionCodingKeys>) throws -> Any
}
extension Condition: DictionaryCondition where T: DictionaryProtocol, T.Value: Codable & Hashable {
    fileprivate static func exists(_ key: String) -> Any { return ConditionExists<T>(key: key) }
    fileprivate static func onKey(structure: inout KeyedDecodingContainer<ConditionCodingKeys>) throws -> Any {
        return try structure.decodeCodable(ConditionOnKey<T>.self, forKey: .OnKey)
    }
}
fileprivate protocol OptionalCondition {
    static func ifNotNull(structure: inout KeyedDecodingContainer<ConditionCodingKeys>) throws -> Any
}
extension Condition: OptionalCondition where T: OptionalType, T.Wrapped: Codable & Hashable {
    fileprivate static func ifNotNull(structure: inout KeyedDecodingContainer<ConditionCodingKeys>) throws -> Any {
        return ConditionIfNotNull(try structure.decode(Condition<T.Wrapped>.self, forKey: .IfNotNull))
    }
}


extension ConditionNever: ConditionProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ConditionCodingKeys>) throws {
        try structure.encode(true, forKey: .Never)
    }
}
extension ConditionAlways: ConditionProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ConditionCodingKeys>) throws {
        try structure.encode(true, forKey: .Always)
    }
}
extension ConditionAnd: ConditionProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ConditionCodingKeys>) throws {
        try structure.encode(conditions, forKey: .And)
    }
}
extension ConditionOr: ConditionProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ConditionCodingKeys>) throws {
        try structure.encode(conditions, forKey: .Or)
    }
}
extension ConditionNot: ConditionProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ConditionCodingKeys>) throws {
        try structure.encode(condition, forKey: .Not)
    }
}
extension ConditionEqual: ConditionProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ConditionCodingKeys>) throws {
        try structure.encode(value, forKey: .Equal)
    }
}
extension ConditionNotEqual: ConditionProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ConditionCodingKeys>) throws {
        try structure.encode(value, forKey: .NotEqual)
    }
}
extension ConditionInside: ConditionProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ConditionCodingKeys>) throws {
        try structure.encode(values, forKey: .Inside)
    }
}
extension ConditionNotInside: ConditionProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ConditionCodingKeys>) throws {
        try structure.encode(values, forKey: .NotInside)
    }
}
extension ConditionGreaterThan: ConditionProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ConditionCodingKeys>) throws {
        try structure.encode(value, forKey: .GreaterThan)
    }
}
extension ConditionLessThan: ConditionProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ConditionCodingKeys>) throws {
        try structure.encode(value, forKey: .LessThan)
    }
}
extension ConditionGreaterThanOrEqual: ConditionProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ConditionCodingKeys>) throws {
        try structure.encode(value, forKey: .GreaterThanOrEqual)
    }
}
extension ConditionLessThanOrEqual: ConditionProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ConditionCodingKeys>) throws {
        try structure.encode(value, forKey: .LessThanOrEqual)
    }
}
extension ConditionFullTextSearch: ConditionProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ConditionCodingKeys>) throws {
        try structure.encodeCodable(self, forKey: .FullTextSearch)
    }
}
extension ConditionIntBitsClear: ConditionProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ConditionCodingKeys>) throws {
        try structure.encode(mask, forKey: .IntBitsClear)
    }
}
extension ConditionIntBitsSet: ConditionProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ConditionCodingKeys>) throws {
        try structure.encode(mask, forKey: .IntBitsSet)
    }
}
extension ConditionIntBitsAnyClear: ConditionProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ConditionCodingKeys>) throws {
        try structure.encode(mask, forKey: .IntBitsAnyClear)
    }
}
extension ConditionIntBitsAnySet: ConditionProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ConditionCodingKeys>) throws {
        try structure.encode(mask, forKey: .IntBitsAnySet)
    }
}
extension ConditionAllElements: ConditionProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ConditionCodingKeys>) throws {
        try structure.encode(condition, forKey: .AllElements)
    }
}
extension ConditionAnyElements: ConditionProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ConditionCodingKeys>) throws {
        try structure.encode(condition, forKey: .AnyElements)
    }
}
extension ConditionSizesEquals: ConditionProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ConditionCodingKeys>) throws {
        try structure.encode(count, forKey: .SizesEquals)
    }
}
extension ConditionExists: ConditionProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ConditionCodingKeys>) throws {
        try structure.encode(self.key, forKey: .Exists)
    }
}
extension ConditionOnKey: ConditionProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ConditionCodingKeys>) throws {
        try structure.encodeCodable(self, forKey: .OnKey)
    }
}
extension ConditionIfNotNull: ConditionProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ConditionCodingKeys>) throws {
        try structure.encode(condition, forKey: .IfNotNull)
    }
}
extension ConditionOnField: ConditionProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ConditionCodingKeys>) throws {
        try structure.encode(condition, forKey: ConditionCodingKeys(stringValue: self.key.name))
    }
}

extension Condition: AltCodable {
    public static func encode(_ value: Condition, to encoder: Encoder) throws {
        var structure = encoder.container(keyedBy: ConditionCodingKeys.self)
        guard let value = value as? ConditionProtocol else { fatalError() }
        try value.encodeSelfInStructure(structure: &structure)
    }

    public static func decode(from decoder: Decoder) throws -> Self {
        var structure = try decoder.container(keyedBy: ConditionCodingKeys.self)
        let key = structure.allKeys.first!
        switch key {
            case .Never:
                return ConditionNever<T>() as! Self
            case .Always:
                return ConditionAlways<T>() as! Self
            case .And:
                return ConditionAnd<T>(conditions: try structure.decode(Array<Condition>.self, forKey: key)) as! Self
            case .Or:
                return ConditionOr<T>(conditions: try structure.decode(Array<Condition>.self, forKey: key)) as! Self
            case .Not:
                return ConditionNot<T>(try structure.decode(Condition.self, forKey: key)) as! Self
            case .Equal:
                return ConditionEqual<T>(try structure.decode(T.self, forKey: key)) as! Self
            case .NotEqual:
                return ConditionNotEqual<T>(try structure.decode(T.self, forKey: key)) as! Self
            case .Inside:
                return ConditionInside<T>(values: try structure.decode(Array<T>.self, forKey: key)) as! Self
            case .NotInside:
                return ConditionNotInside<T>(values: try structure.decode(Array<T>.self, forKey: key)) as! Self
            case .GreaterThan:
                let selfType = (Self.self as! ComparableCondition.Type)
                return selfType.conditionGreaterThan(try structure.decode(T.self, forKey: key)) as! Self
            case .LessThan:
                let selfType = (Self.self as! ComparableCondition.Type)
                return selfType.conditionLessThan(try structure.decode(T.self, forKey: key)) as! Self
            case .GreaterThanOrEqual:
                let selfType = (Self.self as! ComparableCondition.Type)
                return selfType.conditionGreaterThanOrEqual(try structure.decode(T.self, forKey: key)) as! Self
            case .LessThanOrEqual:
                let selfType = (Self.self as! ComparableCondition.Type)
                return selfType.conditionLessThanOrEqual(try structure.decode(T.self, forKey: key)) as! Self
            case .FullTextSearch:
                return ConditionFullTextSearch<String>(try structure.decode(String.self, forKey: key), ignoreCase: true) as! Self
            case .IntBitsClear:
                return ConditionIntBitsClear(mask: try structure.decode(Int.self, forKey: key)) as! Self
            case .IntBitsSet:
                return ConditionIntBitsSet(mask: try structure.decode(Int.self, forKey: key)) as! Self
            case .IntBitsAnyClear:
                return ConditionIntBitsAnyClear(mask: try structure.decode(Int.self, forKey: key)) as! Self
            case .IntBitsAnySet:
                return ConditionIntBitsAnySet(mask: try structure.decode(Int.self, forKey: key)) as! Self
            case .AllElements:
                let selfType = (Self.self as! ArrayCondition.Type)
                return try selfType.all(structure: &structure) as! Self
            case .AnyElements:
                let selfType = (Self.self as! ArrayCondition.Type)
                return try selfType.any(structure: &structure) as! Self
            case .SizesEquals:
                let selfType = (Self.self as! ArrayCondition.Type)
                return selfType.size(try structure.decode(Int.self, forKey: key)) as! Self
            case .Exists:
                let selfType = (Self.self as! DictionaryCondition.Type)
                return selfType.exists(try structure.decode(String.self, forKey: key)) as! Self
            case .OnKey:
                let selfType = (Self.self as! DictionaryCondition.Type)
                return try selfType.onKey(structure: &structure) as! Self
            case .IfNotNull:
                let selfType = (Self.self as! OptionalCondition.Type)
                return try selfType.ifNotNull(structure: &structure) as! Self
            default:
            let prop = (T.self as! AnyPropertyIterable.Type).anyProperties.find { $0.name == key.stringValue } as! PropertyIterablePropertyLike
                return try prop.parse(structure: &structure, key: key) as! Self
        }
    }
}




fileprivate struct ModificationCodingKeys: CodingKey, Hashable {
    let name: String

    static let IfNotNull = ModificationCodingKeys(stringValue: "IfNotNull")
    static let Assign = ModificationCodingKeys(stringValue: "Assign")
    static let CoerceAtMost = ModificationCodingKeys(stringValue: "CoerceAtMost")
    static let CoerceAtLeast = ModificationCodingKeys(stringValue: "CoerceAtLeast")
    static let Increment = ModificationCodingKeys(stringValue: "Increment")
    static let Multiply = ModificationCodingKeys(stringValue: "Multiply")
    static let AppendString = ModificationCodingKeys(stringValue: "AppendString")
    static let AppendList = ModificationCodingKeys(stringValue: "AppendList")
    static let AppendSet = ModificationCodingKeys(stringValue: "AppendSet")
    static let Remove = ModificationCodingKeys(stringValue: "Remove")
    static let RemoveInstances = ModificationCodingKeys(stringValue: "RemoveInstances")
    static let DropFirst = ModificationCodingKeys(stringValue: "DropFirst")
    static let DropLast = ModificationCodingKeys(stringValue: "DropLast")
    static let PerElement = ModificationCodingKeys(stringValue: "PerElement")
    static let Combine = ModificationCodingKeys(stringValue: "Combine")
    static let ModifyByKey = ModificationCodingKeys(stringValue: "ModifyByKey")
    static let RemoveKeys = ModificationCodingKeys(stringValue: "RemoveKeys")
    static let Chain = ModificationCodingKeys(stringValue: "Chain")

    var stringValue: String {
        return self.name
    }

    init(stringValue: String) {
        self.name = stringValue
    }

    var intValue: Int? {
        return nil
    }

    init?(intValue: Int) {
        return nil
    }
}

fileprivate protocol ModificationProtocol {
    func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ModificationCodingKeys>) throws
}

fileprivate protocol ComparableModification {
    static func coerceAtLeast(_ value: Any) -> Any
    static func coerceAtMost(_ value: Any) -> Any
}
extension Modification: ComparableModification where T: Comparable {
    static func coerceAtLeast(_ value: Any) -> Any { return ModificationCoerceAtLeast<T>(value as! T) }
    static func coerceAtMost(_ value: Any) -> Any { return ModificationCoerceAtMost<T>(value as! T) }
}
fileprivate protocol NumberModification {
    static func increment(_ value: Any) -> Any
    static func multiply(_ value: Any) -> Any
}
extension Modification: NumberModification where T: Number {
    static func increment(_ value: Any) -> Any { return ModificationIncrement<T>(by: value as! T) }
    static func multiply(_ value: Any) -> Any { return ModificationMultiply<T>(by: value as! T) }
}
fileprivate protocol ArrayModification {
    static func appendList(value: Any) -> Any
    static func appendSet(value: Any) -> Any
    static func removeInstances(value: Any) -> Any
    static func dropFirst() -> Any
    static func dropLast() -> Any
    static func map(structure: inout KeyedDecodingContainer<ModificationCodingKeys>) throws -> Any
    static func remove(structure: inout KeyedDecodingContainer<ModificationCodingKeys>) throws -> Any
}
extension Modification: ArrayModification where T: Collection, T.Element: Codable & Hashable {
    fileprivate static func appendList(value: Any) -> Any { return ModificationAppendList<T.Element>(items: value as! Array<T.Element>) }
    fileprivate static func appendSet(value: Any) -> Any { return ModificationAppendSet<T.Element>(items: value as! Array<T.Element>) }
    fileprivate static func removeInstances(value: Any) -> Any { return ModificationRemoveInstances<T.Element>(items: value as! Array<T.Element>) }
    static func dropFirst() -> Any { return ModificationDropFirst<T.Element>() }
    static func dropLast() -> Any { return ModificationDropLast<T.Element>() }
    fileprivate static func map(structure: inout KeyedDecodingContainer<ModificationCodingKeys>) throws -> Any {
        return try structure.decodeCodable(ModificationPerElement<T.Element>.self, forKey: .PerElement)
    }
    fileprivate static func remove(structure: inout KeyedDecodingContainer<ModificationCodingKeys>) throws -> Any {
        let condition = try structure.decode(Condition<T.Element>.self, forKey: .Remove)
        return ModificationRemove(condition)
    }
}
fileprivate protocol DictionaryModification {
    static func combine(value: Any) -> Any
    static func modifyByKey(structure: inout KeyedDecodingContainer<ModificationCodingKeys>) throws -> Any
    static func removeKeys(keys: Set<String>) -> Any
}
extension Modification: DictionaryModification where T: DictionaryProtocol, T.Value: Codable & Hashable {
    fileprivate static func combine(value: Any) -> Any { return ModificationCombine(value as! Dictionary<String, T.Value>) }
    fileprivate static func modifyByKey(structure: inout KeyedDecodingContainer<ModificationCodingKeys>) throws -> Any {
        let parts = try structure.decode(Dictionary<String, Modification<T.Value>>.self, forKey: .Remove)
        return ModificationModifyByKey(parts)
    }
    fileprivate static func removeKeys(keys: Set<String>) -> Any {
        return ModificationRemoveKeys<T.Value>(fields: keys)
    }
}
fileprivate protocol OptionalModification {
    static func ifNotNull(structure: inout KeyedDecodingContainer<ModificationCodingKeys>) throws -> Any
}
extension Modification: OptionalModification where T: OptionalType, T.Wrapped: Codable & Hashable {
    fileprivate static func ifNotNull(structure: inout KeyedDecodingContainer<ModificationCodingKeys>) throws -> Any {
        return ModificationIfNotNull(try structure.decode(Modification<T.Wrapped>.self, forKey: .IfNotNull))
    }
}

extension ModificationIfNotNull: ModificationProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ModificationCodingKeys>) throws {
        try structure.encode(modification, forKey: .IfNotNull)
    }
}
extension ModificationAssign: ModificationProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ModificationCodingKeys>) throws {
        try structure.encode(value, forKey: .Assign)
    }
}
extension ModificationCoerceAtMost: ModificationProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ModificationCodingKeys>) throws {
        try structure.encode(value, forKey: .CoerceAtMost)
    }
}
extension ModificationCoerceAtLeast: ModificationProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ModificationCodingKeys>) throws {
        try structure.encode(value, forKey: .CoerceAtLeast)
    }
}
extension ModificationIncrement: ModificationProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ModificationCodingKeys>) throws {
        try structure.encode(by, forKey: .Increment)
    }
}
extension ModificationMultiply: ModificationProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ModificationCodingKeys>) throws {
        try structure.encode(by, forKey: .Multiply)
    }
}
extension ModificationAppendString: ModificationProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ModificationCodingKeys>) throws {
        try structure.encode(value, forKey: .AppendString)
    }
}
extension ModificationAppendList: ModificationProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ModificationCodingKeys>) throws {
        try structure.encode(items, forKey: .AppendList)
    }
}
extension ModificationAppendSet: ModificationProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ModificationCodingKeys>) throws {
        try structure.encode(items, forKey: .AppendSet)
    }
}
extension ModificationRemove: ModificationProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ModificationCodingKeys>) throws {
        try structure.encode(condition, forKey: .Remove)
    }
}
extension ModificationRemoveInstances: ModificationProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ModificationCodingKeys>) throws {
        try structure.encode(items, forKey: .RemoveInstances)
    }
}
extension ModificationDropFirst: ModificationProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ModificationCodingKeys>) throws {
        try structure.encode(true, forKey: .DropFirst)
    }
}
extension ModificationDropLast: ModificationProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ModificationCodingKeys>) throws {
        try structure.encode(true, forKey: .DropLast)
    }
}
extension ModificationPerElement: ModificationProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ModificationCodingKeys>) throws {
        try structure.encodeCodable(self, forKey: .PerElement)
    }
}
extension ModificationCombine: ModificationProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ModificationCodingKeys>) throws {
        try structure.encode(map, forKey: .Combine)
    }
}
extension ModificationModifyByKey: ModificationProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ModificationCodingKeys>) throws {
        try structure.encode(map, forKey: .ModifyByKey)
    }
}
extension ModificationRemoveKeys: ModificationProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ModificationCodingKeys>) throws {
        try structure.encode(self.fields, forKey: .RemoveKeys)
    }
}
extension ModificationOnField: ModificationProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ModificationCodingKeys>) throws {
        try structure.encode(self.modification, forKey: ModificationCodingKeys(stringValue: self.key.name))
    }
}
extension ModificationChain: ModificationProtocol {
    fileprivate func encodeSelfInStructure(structure: inout KeyedEncodingContainer<ModificationCodingKeys>) throws {
        try structure.encode(self.modifications, forKey: ModificationCodingKeys.Chain)
    }
}

extension Modification: AltCodable {
    public static func encode(_ value: Modification, to encoder: Encoder) throws {
        var structure = encoder.container(keyedBy: ModificationCodingKeys.self)
        guard let value = value as? ModificationProtocol else { fatalError() }
        try value.encodeSelfInStructure(structure: &structure)
    }
    
    public static func decode(from decoder: Decoder) throws -> Self {
        var structure = try decoder.container(keyedBy: ModificationCodingKeys.self)
        let key = structure.allKeys.first!
        switch key {
        case .IfNotNull:
            let selfType = (Self.self as! OptionalModification.Type)
            return try selfType.ifNotNull(structure: &structure) as! Self
        case .Assign:
            return ModificationAssign(try structure.decode(T.self, forKey: key)) as! Self
        case .CoerceAtMost:
            let selfType = (Self.self as! ComparableModification.Type)
            return selfType.coerceAtMost(try structure.decode(T.self, forKey: key)) as! Self
        case .CoerceAtLeast:
            let selfType = (Self.self as! ComparableModification.Type)
            return selfType.coerceAtLeast(try structure.decode(T.self, forKey: key)) as! Self
        case .Increment:
            let selfType = (Self.self as! NumberModification.Type)
            return selfType.increment(try structure.decode(T.self, forKey: key)) as! Self
        case .Multiply:
            let selfType = (Self.self as! NumberModification.Type)
            return selfType.multiply(try structure.decode(T.self, forKey: key)) as! Self
        case .AppendString:
            return ModificationAppendString(try structure.decode(String.self, forKey: key)) as! Self
        case .AppendList:
            let selfType = (Self.self as! ArrayModification.Type)
            return selfType.appendList(value: try structure.decode(T.self, forKey: key)) as! Self
        case .AppendSet:
            let selfType = (Self.self as! ArrayModification.Type)
            return selfType.appendList(value: try structure.decode(T.self, forKey: key)) as! Self
        case .Remove:
            let selfType = (Self.self as! ArrayModification.Type)
            return try selfType.remove(structure: &structure) as! Self
        case .RemoveInstances:
            let selfType = (Self.self as! ArrayModification.Type)
            return selfType.removeInstances(value: try structure.decode(T.self, forKey: key)) as! Self
        case .DropFirst:
            try structure.decode(Bool.self, forKey: key)
            let selfType = (Self.self as! ArrayModification.Type)
            return selfType.dropFirst() as! Self
        case .DropLast:
            try structure.decode(Bool.self, forKey: key)
            let selfType = (Self.self as! ArrayModification.Type)
            return selfType.dropLast() as! Self
        case .PerElement:
            let selfType = (Self.self as! ArrayModification.Type)
            return try selfType.map(structure: &structure) as! Self
        case .Combine:
            let selfType = (Self.self as! DictionaryModification.Type)
            return selfType.combine(value: try structure.decode(T.self, forKey: key)) as! Self
        case .ModifyByKey:
            let selfType = (Self.self as! DictionaryModification.Type)
            return try selfType.modifyByKey(structure: &structure) as! Self
        case .RemoveKeys:
            let selfType = (Self.self as! DictionaryModification.Type)
            return selfType.removeKeys(keys: try structure.decode(Set<String>.self, forKey: key)) as! Self
        case .Chain:
            return ModificationChain(modifications: try structure.decode(Array<Modification<T>>.self, forKey: key)) as! Self
        default:
            let prop = (T.self as! AnyPropertyIterable.Type).anyProperties.find { $0.name == key.stringValue } as! PropertyIterablePropertyLike
            return try prop.parseModification(structure: &structure, key: key) as! Self
        }
    }
}
extension SortPart: AltCodable {
    public static func encode(_ value: SortPart, to encoder: Encoder) throws {
        var s = encoder.singleValueContainer()
        if value.ascending {
            try s.encode(value.field.name)
        } else {
            try s.encode("-\(value.field.name)")
        }
    }
    
    public static func decode(from decoder: Decoder) throws -> Self {
        var s = try decoder.singleValueContainer()
        let string = try s.decode(String.self)
        if string.starts(with: "-") {
            let key = string.removePrefix(prefix: "-")
            let prop = (T.self as! AnyPropertyIterable.Type).anyProperties.find { $0.name == key } as! PartialPropertyIterableProperty<T>
            return SortPart(field: prop, ascending: false) as! Self
        } else {
            let prop = (T.self as! AnyPropertyIterable.Type).anyProperties.find { $0.name == string } as! PartialPropertyIterableProperty<T>
            return SortPart(field: prop, ascending: true) as! Self
        }
    }
}

extension PartialPropertyIterableProperty: AltCodable {
    public static func encode(_ value: PartialPropertyIterableProperty<Root>, to encoder: Encoder) throws {
        var s = encoder.singleValueContainer()
        try s.encode(value.name)
    }

    public static func decode(from decoder: Decoder) throws -> Self {
        var s = try decoder.singleValueContainer()
        let string = try s.decode(String.self)
        let x = (Root.self as! AnyPropertyIterable.Type).anyProperties.find { $0.name == string } as? Self
        if let x = x {
            return x
        } else {
            throw Exception("No property named \(string) found")
        }
    }
}
