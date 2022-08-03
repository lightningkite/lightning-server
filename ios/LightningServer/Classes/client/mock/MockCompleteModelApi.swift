// Package: com.lightningkite.lightningdb.mock
// Generated by Khrysalis - this file will be overwritten.
import KhrysalisRuntime
import Foundation

public final class MockCompleteModelApi<Model : HasId> : CompleteModelApi<Model> {
    public var table: MockTable<Model>
    public init(table: MockTable<Model>) {
        self.table = table
        self._read = MockReadModelApi(table: table)
        self._write = MockWriteModelApi(table: table)
        self._observe = MockObserveModelApi(table: table)
        super.init()
        //Necessary properties should be initialized now
    }
    
    public var _read: ReadModelApi<Model>
    override public var read: ReadModelApi<Model> {
        get { return _read }
    }
    public var _write: WriteModelApi<Model>
    override public var write: WriteModelApi<Model> {
        get { return _write }
    }
    public var _observe: ObserveModelApi<Model>
    override public var observe: ObserveModelApi<Model> {
        get { return _observe }
    }
}