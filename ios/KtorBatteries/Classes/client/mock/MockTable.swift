// Package: com.lightningkite.lightningdb.mock
// Generated by Khrysalis - This file has been customized
import KhrysalisRuntime
import RxSwift
import Foundation

public class MockTable<Model : HasId> where Model.ID == UUID {
    public init() {
        self.data = dictionaryOf()
        self.signals = PublishSubject()
        //Necessary properties should be initialized now
    }
    
    
    public var data: Dictionary<UUIDFor<Model>, Model>
    public let signals: PublishSubject<SignalData<Model>>
    
    public func observe(_ condition: Condition<Model>) -> Observable<Array<Model>> {
        return self.signals.map { (it) -> Array<Model> in self.data.values.filter({ (it) -> Bool in condition.invoke(on: it) }) };
    }
    
    public func getItem(id: UUIDFor<Model>) -> Model? {
        return self.data[id];
    }
    
    public func asList() -> Array<Model> {
        return Array(self.data.values);
    }
    
    public func addItem(item: Model) -> Model {
        var array49 = self.data
        array49[item._id] = item
        self.data = array49
        self.signals.onNext(SignalData(item: item, created: true, deleted: false))
        return item
    }
    
    public func replaceItem(item: Model) -> Model {
        var array53 = self.data
        array53[item._id] = item
        self.data = array53
        self.signals.onNext(SignalData(item: item, created: false, deleted: false))
        return item
    }
    
    public func deleteItem(item: Model) -> Void {
        self.deleteItemById(id: item._id)
    }
    
    public func deleteItemById(id: UUIDFor<Model>) -> Void {
        if let item = (self.data[id]) {
            self.data.removeValue(forKey: id)
            self.signals.onNext(SignalData(item: item, created: false, deleted: true))
        }
    }
}
