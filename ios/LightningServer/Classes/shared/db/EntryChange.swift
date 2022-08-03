// Package: com.lightningkite.lightningdb
// Generated by Khrysalis - this file will be overwritten.
import KhrysalisRuntime
import Foundation

public final class EntryChange<T : Codable & Hashable> : CustomStringConvertible, Hashable, Codable {
    public var old: T?
    public var new: T?
    public init(old: T? = nil, new: T? = nil) {
        self.old = old
        self.new = new
        //Necessary properties should be initialized now
    }
    convenience required public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            old: values.contains(.old) ? try values.decode(T?.self, forKey: .old) : nil,
            new: values.contains(.new) ? try values.decode(T?.self, forKey: .new) : nil
        )
    }
    
    enum CodingKeys: String, CodingKey {
        case old = "old"
        case new = "new"
    }
    
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(self.old, forKey: .old)
        try container.encodeIfPresent(self.new, forKey: .new)
    }
    
    public func hash(into hasher: inout Hasher) {
        hasher.combine(old)
        hasher.combine(new)
        
    }
    public static func == (lhs: EntryChange, rhs: EntryChange) -> Bool { return lhs.old == rhs.old && lhs.new == rhs.new }
    public var description: String { return "EntryChange(old=\(String(kotlin: self.old)), new=\(String(kotlin: self.new)))" }
    public func copy(old: T?? = .some(nil), new: T?? = .some(nil)) -> EntryChange<T> { return EntryChange(old: invertOptional(old) ?? self.old, new: invertOptional(new) ?? self.new) }
    
}

public extension EntryChange where T : Codable & Hashable {
    func map<B : Codable & Hashable>(mapper: @escaping (T) -> B) -> EntryChange<B> {
        return (EntryChange<B>(old: self.old.flatMap { temp73 in (mapper)(temp73) } as B?, new: self.new.flatMap { temp74 in (mapper)(temp74) } as B?) as EntryChange<B>)
    }
}
