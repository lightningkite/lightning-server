import { ObserveModelApi } from '../ObserveModelApi';
import { HasId } from '../db/HasId';
import { ListChange } from '../db/ListChange';
import { Query } from '../db/Query';
import { WebSocketIsh } from './sockets';
import { Comparable, Comparator } from '@lightningkite/khrysalis-runtime';
import { Observable } from 'rxjs';
export declare class LiveObserveModelApi<Model extends HasId<string>> extends ObserveModelApi<Model> {
    readonly openSocket: ((query: Query<Model>) => Observable<Array<Model>>);
    constructor(openSocket: ((query: Query<Model>) => Observable<Array<Model>>));
    readonly alreadyOpen: Map<Query<Model>, Observable<Array<Model>>>;
    observe(query: Query<Model>): Observable<Array<Model>>;
}
export declare namespace LiveObserveModelApi {
    class Companion {
        private constructor();
        static INSTANCE: Companion;
        create<Model extends HasId<string>>(Model: Array<any>, multiplexUrl: string, token: string, headers: Map<string, string>, path: string): LiveObserveModelApi<Model>;
    }
}
export declare function xObservableToListObservable<T extends HasId<ID>, ID extends Comparable<ID>>(this_: Observable<ListChange<T>>, ordering: Comparator<T>): Observable<Array<T>>;
export declare function xObservableFilter<T extends HasId<ID>, ID extends Comparable<ID>>(this_: Observable<WebSocketIsh<ListChange<T>, Query<T>>>, query: Query<T>): Observable<Array<T>>;
