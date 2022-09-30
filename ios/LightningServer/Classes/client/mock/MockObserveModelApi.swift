// Package: com.lightningkite.lightningdb.mock
// Generated by Khrysalis
import KhrysalisRuntime
import RxSwift
import RxSwiftPlus
import Foundation

public final class MockObserveModelApi<Model : HasId> : ObserveModelApi<Model> where Model.ID == UUID {
    public var table: MockTable<Model>
    public init(table: MockTable<Model>) {
        self.table = table
        super.init()
        //Necessary properties should be initialized now
    }
    
    override public func observe(_ query: Query<Model>) -> Observable<Array<Model>> {
        return self.table.observe(query.condition).startWith(self.table.asList().filter({ (item) -> Bool in query.condition.invoke(on: item) }))
    }
}
