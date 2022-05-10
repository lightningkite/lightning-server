// Package: com.lightningkite.ktordb.mock
// Generated by Khrysalis - this file will be overwritten.
import KhrysalisRuntime
import Foundation

public class MockFullReadModelApi<Model : HasId> : FullReadModelApi<Model> {
    public var table: MockTable<Model>
    public init(table: MockTable<Model>) {
        self.table = table
        self._read = MockReadModelApi(table: table)
        self._observe = MockObserveModelApi(table: table)
        super.init()
        //Necessary properties should be initialized now
    }
    
    public var _read: ReadModelApi<Model>
    override public var read: ReadModelApi<Model> {
        get { return _read }
    }
    public var _observe: ObserveModelApi<Model>
    override public var observe: ObserveModelApi<Model> {
        get { return _observe }
    }
}
