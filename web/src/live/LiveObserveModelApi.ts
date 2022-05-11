// Package: com.lightningkite.ktordb.live
// Generated by Khrysalis - this file will be overwritten.
import { ObserveModelApi } from '../ObserveModelApi'
import { HasId } from '../db/HasId'
import { ListChange } from '../db/ListChange'
import { Query } from '../db/Query'
import { xListComparatorGet } from '../db/SortPart'
import { WebSocketIsh, multiplexedSocketReified } from './sockets'
import { Comparable, Comparator, EqualOverrideMap, compareBy, listRemoveAll, runOrNull, safeEq, xMutableMapGetOrPut } from '@lightningkite/khrysalis-runtime'
import { Observable } from 'rxjs'
import { finalize, map, publishReplay, refCount, switchMap } from 'rxjs/operators'

//! Declares com.lightningkite.ktordb.live.LiveObserveModelApi
export class LiveObserveModelApi<Model extends HasId> extends ObserveModelApi<Model> {
    public constructor(public readonly openSocket: ((query: Query<Model>) => Observable<Array<Model>>)) {
        super();
        this.alreadyOpen = new EqualOverrideMap<Query<Model>, Observable<Array<Model>>>();
    }
    
    
    
    
    public readonly alreadyOpen: Map<Query<Model>, Observable<Array<Model>>>;
    
    public observe(query: Query<Model>): Observable<Array<Model>> {
        //multiplexedSocket<ListChange<Model>, Query<Model>>("$multiplexUrl?jwt=$token", path)
        return xMutableMapGetOrPut<Query<Model>, Observable<Array<Model>>>(this.alreadyOpen, query, (): Observable<Array<Model>> => (this.openSocket(query).pipe(finalize((): void => {
            this.alreadyOpen.delete(query);
        })).pipe(publishReplay(1)).pipe(refCount())));
    }
}
export namespace LiveObserveModelApi {
    //! Declares com.lightningkite.ktordb.live.LiveObserveModelApi.Companion
    export class Companion {
        private constructor() {
        }
        public static INSTANCE = new Companion();
        
        public create<Model extends HasId>(Model: Array<any>, multiplexUrl: string, token: string, headers: Map<string, string>, path: string): LiveObserveModelApi<Model> {
            return new LiveObserveModelApi<Model>((query: Query<Model>): Observable<Array<Model>> => (xObservableToListObservable<Model>(multiplexedSocketReified<ListChange<Model>, Query<Model>>([ListChange, Model], [Query, Model], `${multiplexUrl}?jwt=${token}`, path, undefined).pipe(switchMap((it: WebSocketIsh<ListChange<Model>, Query<Model>>): Observable<ListChange<Model>> => {
                it.send(query);
                return it.messages;
            })), xListComparatorGet(query.orderBy) ?? compareBy<Model>((it: Model): (Comparable<(any | null)> | null) => (it._id)))));
        }
    }
}

//! Declares com.lightningkite.ktordb.live.toListObservable>io.reactivex.rxjava3.core.Observablecom.lightningkite.ktordb.ListChangecom.lightningkite.ktordb.live.toListObservable.T
export function xObservableToListObservable<T extends HasId>(this_: Observable<ListChange<T>>, ordering: Comparator<T>): Observable<Array<T>> {
    const localList = ([] as Array<T>);
    return this_.pipe(map((it: ListChange<T>): Array<T> => {
        const it_9 = it.wholeList;
        if (it_9 !== null) {
            localList.length = 0; localList.push(...it_9.slice().sort(ordering));
        }
        const it_11 = it._new;
        if (it_11 !== null) {
            listRemoveAll(localList, (o: T): boolean => (safeEq(it_11._id, o._id)));
            let index = localList.findIndex((inList: T): boolean => (ordering(it_11, inList) < 0));
            if (index === (-1)) { index = localList.length }
            localList.splice(index, 0, it_11);
        } else {
            const it_18 = it.old;
            if (it_18 !== null) {
                listRemoveAll(localList, (o: T): boolean => (safeEq(it_18._id, o._id)));
            }
        }
        return localList;
    }));
}