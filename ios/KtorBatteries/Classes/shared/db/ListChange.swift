// Package: com.lightningkite.lightningdb
// Generated by Khrysalis - this file will be overwritten.
import KhrysalisRuntime
import Foundation

public class ListChange<T : Codable & Hashable> : CustomStringConvertible, Hashable, Codable {
    public var wholeList: Array<T>?
    public var old: T?
    public var new: T?
    public init(wholeList: Array<T>? = nil, old: T? = nil, new: T? = nil) {
        self.wholeList = wholeList
        self.old = old
        self.new = new
        //Necessary properties should be initialized now
    }
    convenience required public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            wholeList: values.contains(.wholeList) ? try values.decode(Array<T>?.self, forKey: .wholeList) : nil,
            old: values.contains(.old) ? try values.decode(T?.self, forKey: .old) : nil,
            new: values.contains(.new) ? try values.decode(T?.self, forKey: .new) : nil
        )
    }
    
    enum CodingKeys: String, CodingKey {
        case wholeList = "wholeList"
        case old = "old"
        case new = "new"
    }
    
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(self.wholeList, forKey: .wholeList)
        try container.encodeIfPresent(self.old, forKey: .old)
        try container.encodeIfPresent(self.new, forKey: .new)
    }
    
    public func hash(into hasher: inout Hasher) {
        hasher.combine(wholeList)
        hasher.combine(old)
        hasher.combine(new)
        
    }
    public static func == (lhs: ListChange, rhs: ListChange) -> Bool { return lhs.wholeList == rhs.wholeList && lhs.old == rhs.old && lhs.new == rhs.new }
    public var description: String { return "ListChange(wholeList=\(String(kotlin: self.wholeList)), old=\(String(kotlin: self.old)), new=\(String(kotlin: self.new)))" }
    public func copy(wholeList: Array<T>?? = .some(nil), old: T?? = .some(nil), new: T?? = .some(nil)) -> ListChange<T> { return ListChange(wholeList: invertOptional(wholeList) ?? self.wholeList, old: invertOptional(old) ?? self.old, new: invertOptional(new) ?? self.new) }
}

public extension EntryChange where T : Codable & Hashable {
    func listChange() -> ListChange<T> {
        return ListChange(old: self.old, new: self.new);
    }
}
