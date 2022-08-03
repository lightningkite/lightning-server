// Package: com.lightningkite.lightningdb
// Generated by Khrysalis - this file will be overwritten.
import KhrysalisRuntime
import Foundation

public class Condition<T : Codable & Hashable> : KEquatable, KHashable {
    public init() {
        //Necessary properties should be initialized now
    }
    
    open func hashCode() -> Int { fatalError() }
    open func equals(other: Any) -> Bool { fatalError() }
    
    open func invoke(on: T) -> Bool { fatalError() }
    open func simplify() -> Condition<T> {
        return self;
    }
    
    public func and(other: Condition<T>) -> ConditionAnd<T> {
        return ConditionAnd(conditions: [self, other]);
    }
    public func or(other: Condition<T>) -> ConditionOr<T> {
        return ConditionOr(conditions: [self, other]);
    }
    public func not() -> ConditionNot<T> {
        return ConditionNot(self);
    }
    static prefix func !(receiver: Condition<T>) -> ConditionNot<T> { receiver.not() }
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
}

public final class ConditionNever<T : Codable & Hashable> : Condition<T> {
    override public init() {
        super.init()
        //Necessary properties should be initialized now
    }
    
    override public func invoke(on: T) -> Bool {
        return false;
    }
    override public func hashCode() -> Int {
        return 0;
    }
    override public func equals(other: Any) -> Bool {
        return (other as? ConditionNever<T>) != nil;
    }
}
public final class ConditionAlways<T : Codable & Hashable> : Condition<T> {
    override public init() {
        super.init()
        //Necessary properties should be initialized now
    }
    
    override public func invoke(on: T) -> Bool {
        return true;
    }
    override public func hashCode() -> Int {
        return 1;
    }
    override public func equals(other: Any) -> Bool {
        return (other as? ConditionAlways<T>) != nil;
    }
}
public final class ConditionAnd<T : Codable & Hashable> : Condition<T>, CustomStringConvertible {
    public var conditions: Array<Condition<T>>
    public init(conditions: Array<Condition<T>>) {
        self.conditions = conditions
        super.init()
        //Necessary properties should be initialized now
    }
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(conditions)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? ConditionAnd else { return false }
        return self.conditions == other.conditions
    }
    public var description: String { return "ConditionAnd(conditions=\(String(kotlin: self.conditions)))" }
    public func copy(conditions: Array<Condition<T>>? = nil) -> ConditionAnd<T> { return ConditionAnd(conditions: conditions ?? self.conditions) }
    
    override public func invoke(on: T) -> Bool {
        return self.conditions.allSatisfy({ (it) -> Bool in it.invoke(on: on) });
    }
    override public func simplify() -> Condition<T> {
        return self.conditions.isEmpty ? ConditionAlways() : ConditionAnd<T>(conditions: self.conditions.distinct());
    }
}
public final class ConditionOr<T : Codable & Hashable> : Condition<T>, CustomStringConvertible {
    public var conditions: Array<Condition<T>>
    public init(conditions: Array<Condition<T>>) {
        self.conditions = conditions
        super.init()
        //Necessary properties should be initialized now
    }
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(conditions)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? ConditionOr else { return false }
        return self.conditions == other.conditions
    }
    public var description: String { return "ConditionOr(conditions=\(String(kotlin: self.conditions)))" }
    public func copy(conditions: Array<Condition<T>>? = nil) -> ConditionOr<T> { return ConditionOr(conditions: conditions ?? self.conditions) }
    
    override public func invoke(on: T) -> Bool {
        return (self.conditions.first(where: { (it) -> Bool in it.invoke(on: on) }) != nil);
    }
    override public func simplify() -> Condition<T> {
        return self.conditions.isEmpty ? ConditionNever() : ConditionOr<T>(conditions: self.conditions.distinct());
    }
}
public final class ConditionNot<T : Codable & Hashable> : Condition<T>, CustomStringConvertible {
    public var condition: Condition<T>
    public init(_ condition: Condition<T>) {
        self.condition = condition
        super.init()
        //Necessary properties should be initialized now
    }
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(condition)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? ConditionNot else { return false }
        return self.condition == other.condition
    }
    public var description: String { return "ConditionNot(condition=\(String(kotlin: self.condition)))" }
    public func copy(_ condition: Condition<T>? = nil) -> ConditionNot<T> { return ConditionNot(condition ?? self.condition) }
    
    override public func invoke(on: T) -> Bool {
        return (!self.condition.invoke(on: on));
    }
    override public func simplify() -> Condition<T> {
        return (self.condition as? ConditionNot<T>)?.condition ?? self;
    }
}
public final class ConditionEqual<T : Codable & Hashable> : Condition<T>, CustomStringConvertible {
    public var value: T
    public init(_ value: T) {
        self.value = value
        super.init()
        //Necessary properties should be initialized now
    }
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(value)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? ConditionEqual else { return false }
        return self.value == other.value
    }
    public var description: String { return "ConditionEqual(value=\(String(kotlin: self.value)))" }
    public func copy(_ value: T? = nil) -> ConditionEqual<T> { return ConditionEqual(value ?? self.value) }
    override public func invoke(on: T) -> Bool {
        return on == self.value;
} }
public final class ConditionNotEqual<T : Codable & Hashable> : Condition<T>, CustomStringConvertible {
    public var value: T
    public init(_ value: T) {
        self.value = value
        super.init()
        //Necessary properties should be initialized now
    }
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(value)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? ConditionNotEqual else { return false }
        return self.value == other.value
    }
    public var description: String { return "ConditionNotEqual(value=\(String(kotlin: self.value)))" }
    public func copy(_ value: T? = nil) -> ConditionNotEqual<T> { return ConditionNotEqual(value ?? self.value) }
    override public func invoke(on: T) -> Bool {
        return on != self.value;
} }
public final class ConditionInside<T : Codable & Hashable> : Condition<T>, CustomStringConvertible {
    public var values: Array<T>
    public init(values: Array<T>) {
        self.values = values
        super.init()
        //Necessary properties should be initialized now
    }
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(values)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? ConditionInside else { return false }
        return self.values == other.values
    }
    public var description: String { return "ConditionInside(values=\(String(kotlin: self.values)))" }
    public func copy(values: Array<T>? = nil) -> ConditionInside<T> { return ConditionInside(values: values ?? self.values) }
    override public func invoke(on: T) -> Bool {
        return self.values.contains(on);
} }
public final class ConditionNotInside<T : Codable & Hashable> : Condition<T>, CustomStringConvertible {
    public var values: Array<T>
    public init(values: Array<T>) {
        self.values = values
        super.init()
        //Necessary properties should be initialized now
    }
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(values)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? ConditionNotInside else { return false }
        return self.values == other.values
    }
    public var description: String { return "ConditionNotInside(values=\(String(kotlin: self.values)))" }
    public func copy(values: Array<T>? = nil) -> ConditionNotInside<T> { return ConditionNotInside(values: values ?? self.values) }
    override public func invoke(on: T) -> Bool {
        return (!self.values.contains(on));
} }
public final class ConditionGreaterThan<T : Codable & Hashable & Comparable> : Condition<T>, CustomStringConvertible {
    public var value: T
    public init(_ value: T) {
        self.value = value
        super.init()
        //Necessary properties should be initialized now
    }
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(value)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? ConditionGreaterThan else { return false }
        return self.value == other.value
    }
    public var description: String { return "ConditionGreaterThan(value=\(String(kotlin: self.value)))" }
    public func copy(_ value: T? = nil) -> ConditionGreaterThan<T> { return ConditionGreaterThan(value ?? self.value) }
    override public func invoke(on: T) -> Bool {
        return on > self.value;
} }
public final class ConditionLessThan<T : Codable & Hashable & Comparable> : Condition<T>, CustomStringConvertible {
    public var value: T
    public init(_ value: T) {
        self.value = value
        super.init()
        //Necessary properties should be initialized now
    }
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(value)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? ConditionLessThan else { return false }
        return self.value == other.value
    }
    public var description: String { return "ConditionLessThan(value=\(String(kotlin: self.value)))" }
    public func copy(_ value: T? = nil) -> ConditionLessThan<T> { return ConditionLessThan(value ?? self.value) }
    override public func invoke(on: T) -> Bool {
        return on < self.value;
} }
public final class ConditionGreaterThanOrEqual<T : Codable & Hashable & Comparable> : Condition<T>, CustomStringConvertible {
    public var value: T
    public init(_ value: T) {
        self.value = value
        super.init()
        //Necessary properties should be initialized now
    }
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(value)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? ConditionGreaterThanOrEqual else { return false }
        return self.value == other.value
    }
    public var description: String { return "ConditionGreaterThanOrEqual(value=\(String(kotlin: self.value)))" }
    public func copy(_ value: T? = nil) -> ConditionGreaterThanOrEqual<T> { return ConditionGreaterThanOrEqual(value ?? self.value) }
    override public func invoke(on: T) -> Bool {
        return on >= self.value;
} }
public final class ConditionLessThanOrEqual<T : Codable & Hashable & Comparable> : Condition<T>, CustomStringConvertible {
    public var value: T
    public init(_ value: T) {
        self.value = value
        super.init()
        //Necessary properties should be initialized now
    }
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(value)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? ConditionLessThanOrEqual else { return false }
        return self.value == other.value
    }
    public var description: String { return "ConditionLessThanOrEqual(value=\(String(kotlin: self.value)))" }
    public func copy(_ value: T? = nil) -> ConditionLessThanOrEqual<T> { return ConditionLessThanOrEqual(value ?? self.value) }
    override public func invoke(on: T) -> Bool {
        return on <= self.value;
} }
public final class ConditionStringContains : Condition<String>, CustomStringConvertible, Codable {
    public var value: String
    public var ignoreCase: Bool
    public init(_ value: String, ignoreCase: Bool = false) {
        self.value = value
        self.ignoreCase = ignoreCase
        super.init()
        //Necessary properties should be initialized now
    }
    convenience required public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            try values.decode(String.self, forKey: .value),
            ignoreCase: values.contains(.ignoreCase) ? try values.decode(Bool.self, forKey: .ignoreCase) : false
        )
    }
    
    enum CodingKeys: String, CodingKey {
        case value = "value"
        case ignoreCase = "ignoreCase"
    }
    
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(self.value, forKey: .value)
        try container.encode(self.ignoreCase, forKey: .ignoreCase)
    }
    
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(value)
        hasher.combine(ignoreCase)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? ConditionStringContains else { return false }
        return self.value == other.value && self.ignoreCase == other.ignoreCase
    }
    public var description: String { return "ConditionStringContains(value=\(String(kotlin: self.value)), ignoreCase=\(String(kotlin: self.ignoreCase)))" }
    public func copy(_ value: String? = nil, ignoreCase: Bool? = nil) -> ConditionStringContains { return ConditionStringContains(value ?? self.value, ignoreCase: ignoreCase ?? self.ignoreCase) }
    override public func invoke(on: String) -> Bool {
        return (on.indexOf(self.value) != -1);
} }
public final class ConditionFullTextSearch<T : Codable & Hashable> : Condition<T>, CustomStringConvertible, Codable {
    public var value: String
    public var ignoreCase: Bool
    public init(_ value: String, ignoreCase: Bool = false) {
        self.value = value
        self.ignoreCase = ignoreCase
        super.init()
        //Necessary properties should be initialized now
    }
    convenience required public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            try values.decode(String.self, forKey: .value),
            ignoreCase: values.contains(.ignoreCase) ? try values.decode(Bool.self, forKey: .ignoreCase) : false
        )
    }
    
    enum CodingKeys: String, CodingKey {
        case value = "value"
        case ignoreCase = "ignoreCase"
    }
    
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(self.value, forKey: .value)
        try container.encode(self.ignoreCase, forKey: .ignoreCase)
    }
    
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(value)
        hasher.combine(ignoreCase)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? ConditionFullTextSearch else { return false }
        return self.value == other.value && self.ignoreCase == other.ignoreCase
    }
    public var description: String { return "ConditionFullTextSearch(value=\(String(kotlin: self.value)), ignoreCase=\(String(kotlin: self.ignoreCase)))" }
    public func copy(_ value: String? = nil, ignoreCase: Bool? = nil) -> ConditionFullTextSearch<T> { return ConditionFullTextSearch(value ?? self.value, ignoreCase: ignoreCase ?? self.ignoreCase) }
    
    override public func invoke(on: T) -> Bool {
        fatalError("Not Implemented locally")
    }
}
public final class ConditionRegexMatches : Condition<String>, CustomStringConvertible, Codable {
    public var pattern: String
    public var ignoreCase: Bool
    public init(pattern: String, ignoreCase: Bool = false) {
        self.pattern = pattern
        self.ignoreCase = ignoreCase
        self.regex = (try! NSRegularExpression(pattern: pattern, options: []))
        super.init()
        //Necessary properties should be initialized now
    }
    convenience required public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            pattern: try values.decode(String.self, forKey: .pattern),
            ignoreCase: values.contains(.ignoreCase) ? try values.decode(Bool.self, forKey: .ignoreCase) : false
        )
    }
    
    enum CodingKeys: String, CodingKey {
        case pattern = "pattern"
        case ignoreCase = "ignoreCase"
    }
    
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(self.pattern, forKey: .pattern)
        try container.encode(self.ignoreCase, forKey: .ignoreCase)
    }
    
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(pattern)
        hasher.combine(ignoreCase)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? ConditionRegexMatches else { return false }
        return self.pattern == other.pattern && self.ignoreCase == other.ignoreCase
    }
    public var description: String { return "ConditionRegexMatches(pattern=\(String(kotlin: self.pattern)), ignoreCase=\(String(kotlin: self.ignoreCase)))" }
    public func copy(pattern: String? = nil, ignoreCase: Bool? = nil) -> ConditionRegexMatches { return ConditionRegexMatches(pattern: pattern ?? self.pattern, ignoreCase: ignoreCase ?? self.ignoreCase) }
    
    public let regex: NSRegularExpression
    override public func invoke(on: String) -> Bool {
        return self.regex.matches(input: on);
    }
}
public final class ConditionIntBitsClear : Condition<Int>, CustomStringConvertible {
    public var mask: Int
    public init(mask: Int) {
        self.mask = mask
        super.init()
        //Necessary properties should be initialized now
    }
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(mask)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? ConditionIntBitsClear else { return false }
        return self.mask == other.mask
    }
    public var description: String { return "ConditionIntBitsClear(mask=\(String(kotlin: self.mask)))" }
    public func copy(mask: Int? = nil) -> ConditionIntBitsClear { return ConditionIntBitsClear(mask: mask ?? self.mask) }
    override public func invoke(on: Int) -> Bool {
        return on & self.mask == 0;
} }
public final class ConditionIntBitsSet : Condition<Int>, CustomStringConvertible {
    public var mask: Int
    public init(mask: Int) {
        self.mask = mask
        super.init()
        //Necessary properties should be initialized now
    }
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(mask)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? ConditionIntBitsSet else { return false }
        return self.mask == other.mask
    }
    public var description: String { return "ConditionIntBitsSet(mask=\(String(kotlin: self.mask)))" }
    public func copy(mask: Int? = nil) -> ConditionIntBitsSet { return ConditionIntBitsSet(mask: mask ?? self.mask) }
    override public func invoke(on: Int) -> Bool {
        return on & self.mask == self.mask;
} }
public final class ConditionIntBitsAnyClear : Condition<Int>, CustomStringConvertible {
    public var mask: Int
    public init(mask: Int) {
        self.mask = mask
        super.init()
        //Necessary properties should be initialized now
    }
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(mask)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? ConditionIntBitsAnyClear else { return false }
        return self.mask == other.mask
    }
    public var description: String { return "ConditionIntBitsAnyClear(mask=\(String(kotlin: self.mask)))" }
    public func copy(mask: Int? = nil) -> ConditionIntBitsAnyClear { return ConditionIntBitsAnyClear(mask: mask ?? self.mask) }
    override public func invoke(on: Int) -> Bool {
        return on & self.mask < self.mask;
} }
public final class ConditionIntBitsAnySet : Condition<Int>, CustomStringConvertible {
    public var mask: Int
    public init(mask: Int) {
        self.mask = mask
        super.init()
        //Necessary properties should be initialized now
    }
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(mask)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? ConditionIntBitsAnySet else { return false }
        return self.mask == other.mask
    }
    public var description: String { return "ConditionIntBitsAnySet(mask=\(String(kotlin: self.mask)))" }
    public func copy(mask: Int? = nil) -> ConditionIntBitsAnySet { return ConditionIntBitsAnySet(mask: mask ?? self.mask) }
    override public func invoke(on: Int) -> Bool {
        return on & self.mask > 0;
} }
public final class ConditionListAllElements<E : Codable & Hashable> : Condition<Array<E>>, CustomStringConvertible {
    public var condition: Condition<E>
    public init(_ condition: Condition<E>) {
        self.condition = condition
        super.init()
        //Necessary properties should be initialized now
    }
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(condition)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? ConditionListAllElements else { return false }
        return self.condition == other.condition
    }
    public var description: String { return "ConditionListAllElements(condition=\(String(kotlin: self.condition)))" }
    public func copy(_ condition: Condition<E>? = nil) -> ConditionListAllElements<E> { return ConditionListAllElements(condition ?? self.condition) }
    override public func invoke(on: Array<E>) -> Bool {
        return on.allSatisfy({ (it) -> Bool in self.condition.invoke(on: it) });
} }
public final class ConditionListAnyElements<E : Codable & Hashable> : Condition<Array<E>>, CustomStringConvertible {
    public var condition: Condition<E>
    public init(_ condition: Condition<E>) {
        self.condition = condition
        super.init()
        //Necessary properties should be initialized now
    }
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(condition)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? ConditionListAnyElements else { return false }
        return self.condition == other.condition
    }
    public var description: String { return "ConditionListAnyElements(condition=\(String(kotlin: self.condition)))" }
    public func copy(_ condition: Condition<E>? = nil) -> ConditionListAnyElements<E> { return ConditionListAnyElements(condition ?? self.condition) }
    override public func invoke(on: Array<E>) -> Bool {
        return (on.first(where: { (it) -> Bool in self.condition.invoke(on: it) }) != nil);
} }
public final class ConditionListSizesEquals<E : Codable & Hashable> : Condition<Array<E>>, CustomStringConvertible {
    public var count: Int
    public init(count: Int) {
        self.count = count
        super.init()
        //Necessary properties should be initialized now
    }
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(count)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? ConditionListSizesEquals else { return false }
        return self.count == other.count
    }
    public var description: String { return "ConditionListSizesEquals(count=\(String(kotlin: self.count)))" }
    public func copy(count: Int? = nil) -> ConditionListSizesEquals<E> { return ConditionListSizesEquals(count: count ?? self.count) }
    override public func invoke(on: Array<E>) -> Bool {
        return on.count == self.count;
} }
public final class ConditionSetAllElements<E : Codable & Hashable> : Condition<Set<E>>, CustomStringConvertible {
    public var condition: Condition<E>
    public init(_ condition: Condition<E>) {
        self.condition = condition
        super.init()
        //Necessary properties should be initialized now
    }
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(condition)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? ConditionSetAllElements else { return false }
        return self.condition == other.condition
    }
    public var description: String { return "ConditionSetAllElements(condition=\(String(kotlin: self.condition)))" }
    public func copy(_ condition: Condition<E>? = nil) -> ConditionSetAllElements<E> { return ConditionSetAllElements(condition ?? self.condition) }
    override public func invoke(on: Set<E>) -> Bool {
        return on.allSatisfy({ (it) -> Bool in self.condition.invoke(on: it) });
} }
public final class ConditionSetAnyElements<E : Codable & Hashable> : Condition<Set<E>>, CustomStringConvertible {
    public var condition: Condition<E>
    public init(_ condition: Condition<E>) {
        self.condition = condition
        super.init()
        //Necessary properties should be initialized now
    }
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(condition)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? ConditionSetAnyElements else { return false }
        return self.condition == other.condition
    }
    public var description: String { return "ConditionSetAnyElements(condition=\(String(kotlin: self.condition)))" }
    public func copy(_ condition: Condition<E>? = nil) -> ConditionSetAnyElements<E> { return ConditionSetAnyElements(condition ?? self.condition) }
    override public func invoke(on: Set<E>) -> Bool {
        return (on.first(where: { (it) -> Bool in self.condition.invoke(on: it) }) != nil);
} }
public final class ConditionSetSizesEquals<E : Codable & Hashable> : Condition<Set<E>>, CustomStringConvertible {
    public var count: Int
    public init(count: Int) {
        self.count = count
        super.init()
        //Necessary properties should be initialized now
    }
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(count)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? ConditionSetSizesEquals else { return false }
        return self.count == other.count
    }
    public var description: String { return "ConditionSetSizesEquals(count=\(String(kotlin: self.count)))" }
    public func copy(count: Int? = nil) -> ConditionSetSizesEquals<E> { return ConditionSetSizesEquals(count: count ?? self.count) }
    override public func invoke(on: Set<E>) -> Bool {
        return on.count == self.count;
} }
public final class ConditionExists<V : Codable & Hashable> : Condition<Dictionary<String, V>>, CustomStringConvertible {
    public var key: String
    public init(key: String) {
        self.key = key
        super.init()
        //Necessary properties should be initialized now
    }
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(key)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? ConditionExists else { return false }
        return self.key == other.key
    }
    public var description: String { return "ConditionExists(key=\(String(kotlin: self.key)))" }
    public func copy(key: String? = nil) -> ConditionExists<V> { return ConditionExists(key: key ?? self.key) }
    override public func invoke(on: Dictionary<String, V>) -> Bool {
        return (on.index(forKey: self.key) != nil);
} }
public final class ConditionOnKey<V : Codable & Hashable> : Condition<Dictionary<String, V>>, CustomStringConvertible, Codable {
    public var key: String
    public var condition: Condition<V>
    public init(key: String, condition: Condition<V>) {
        self.key = key
        self.condition = condition
        super.init()
        //Necessary properties should be initialized now
    }
    convenience required public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            key: try values.decode(String.self, forKey: .key),
            condition: try values.decode(Condition<V>.self, forKey: .condition)
        )
    }
    
    enum CodingKeys: String, CodingKey {
        case key = "key"
        case condition = "condition"
    }
    
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(self.key, forKey: .key)
        try container.encode(self.condition, forKey: .condition)
    }
    
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(key)
        hasher.combine(condition)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? ConditionOnKey else { return false }
        return self.key == other.key && self.condition == other.condition
    }
    public var description: String { return "ConditionOnKey(key=\(String(kotlin: self.key)), condition=\(String(kotlin: self.condition)))" }
    public func copy(key: String? = nil, condition: Condition<V>? = nil) -> ConditionOnKey<V> { return ConditionOnKey(key: key ?? self.key, condition: condition ?? self.condition) }
    override public func invoke(on: Dictionary<String, V>) -> Bool {
        return (on.index(forKey: self.key) != nil) && self.condition.invoke(on: on[self.key] as! V);
} }
public final class ConditionOnField<K : Codable & Hashable, V : Codable & Hashable> : Condition<K>, CustomStringConvertible {
    public var key: PropertyIterableProperty<K, V>
    public var condition: Condition<V>
    public init(key: PropertyIterableProperty<K, V>, condition: Condition<V>) {
        self.key = key
        self.condition = condition
        super.init()
        //Necessary properties should be initialized now
    }
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(key)
        hasher.combine(condition)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? ConditionOnField else { return false }
        return self.key == other.key && self.condition == other.condition
    }
    public var description: String { return "ConditionOnField(key=\(String(kotlin: self.key)), condition=\(String(kotlin: self.condition)))" }
    public func copy(key: PropertyIterableProperty<K, V>? = nil, condition: Condition<V>? = nil) -> ConditionOnField<K, V> { return ConditionOnField(key: key ?? self.key, condition: condition ?? self.condition) }
    override public func invoke(on: K) -> Bool {
        return self.condition.invoke(on: self.key.get(on));
} }
public final class ConditionIfNotNull<T : Codable & Hashable> : Condition<T?>, CustomStringConvertible {
    public var condition: Condition<T>
    public init(_ condition: Condition<T>) {
        self.condition = condition
        super.init()
        //Necessary properties should be initialized now
    }
    override public func hashCode() -> Int {
        var hasher = Hasher()
        hasher.combine(condition)
        return hasher.finalize()
    }
    override public func equals(other: Any) -> Bool {
        guard let other = other as? ConditionIfNotNull else { return false }
        return self.condition == other.condition
    }
    public var description: String { return "ConditionIfNotNull(condition=\(String(kotlin: self.condition)))" }
    public func copy(_ condition: Condition<T>? = nil) -> ConditionIfNotNull<T> { return ConditionIfNotNull(condition ?? self.condition) }
    override public func invoke(on: T?) -> Bool {
        return on != nil && self.condition.invoke(on: on!);
} }