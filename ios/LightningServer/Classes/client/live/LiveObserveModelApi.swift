// Package: com.lightningkite.lightningdb.live
// Generated by Khrysalis - this file will be overwritten.
import KhrysalisRuntime
import RxSwift
import Foundation

public final class LiveObserveModelApi<Model : HasId> : ObserveModelApi<Model> {
    public var openSocket: (Query<Model>) -> Observable<Array<Model>>
    public init(openSocket: @escaping (Query<Model>) -> Observable<Array<Model>>) {
        self.openSocket = openSocket
        self.alreadyOpen = Dictionary<Query<Model>, Observable<Array<Model>>>()
        super.init()
        //Necessary properties should be initialized now
    }
    
    
    
    
    public var alreadyOpen: Dictionary<Query<Model>, Observable<Array<Model>>>
    
    override public func observe(_ query: Query<Model>) -> Observable<Array<Model>> {
        //multiplexedSocket<ListChange<Model>, Query<Model>>("$multiplexUrl?jwt=$token", path)
        return self.alreadyOpen.getOrPut(key: query) { () -> Observable<Array<Model>> in self.openSocket(query)
                .doOnDispose { () -> Void in self.alreadyOpen.removeValue(forKey: query) }
                .replay(1)
            .refCount() }
    }
}

public func xObservableToListObservable<T : HasId>(_ this: Observable<ListChange<T>>, ordering: @escaping TypedComparator<T>) -> Observable<Array<T>> {
    var localList = [] as Array<T>
    return this.map({ (it) -> Array<T> in
        if let it = (it.wholeList) {
            localList.removeAll(); localList.insert(contentsOf: it.sorted(by: ordering), at: localList.count)
        }
        if let it = (it.new) {
            localList.removeAll(where: { (o) -> Bool in it._id == o._id })
            var index = (localList.firstIndex(where: { (inList) -> Bool in ordering(it, inList).rawValue < 0 }) ?? -1)
            if index == (-1) { index = localList.count }
            localList.insert(it, at: index)
        } else if let it = (it.old) {
            localList.removeAll(where: { (o) -> Bool in it._id == o._id })
        }
        return localList
    })
}
public final class LiveObserveModelApiCompanion {
    public init() {
        //Necessary properties should be initialized now
    }
    public static let INSTANCE = LiveObserveModelApiCompanion()
    
    public func create<Model : HasId>(multiplexUrl: String, token: String, headers: Dictionary<String, String>, path: String) -> LiveObserveModelApi<Model> {
        return LiveObserveModelApi<Model>(openSocket: { (query) -> Observable<Array<Model>> in xObservableToListObservable((multiplexedSocket(url: multiplexUrl, path: path, queryParams: dictionaryOf(Pair("jwt", [token]))) as Observable<WebSocketIsh<ListChange<Model>, Query<Model>>>)
                .switchMap { (it) -> Observable<ListChange<Model>> in
                it.send(query)
                return it.messages.catchError({ (it) -> Observable<ListChange<Model>> in Observable.never() })
        }, ordering: getListComparator(query.orderBy) ?? compareBy(selector: { (it) in it._id })) });
    }
}
