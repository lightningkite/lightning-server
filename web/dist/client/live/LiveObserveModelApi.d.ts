import { HasId } from '../../shared/HasId';
import { ListChange } from '../../shared/ListChange';
import { Query } from '../../shared/Query';
import { ObserveModelApi } from '../ObserveModelApi';
import { Comparator } from '@lightningkite/khrysalis-runtime';
import { Observable } from 'rxjs';
export declare class LiveObserveModelApi<Model extends HasId> extends ObserveModelApi<Model> {
    readonly openSocket: ((query: Query<Model>) => Observable<Array<Model>>);
    constructor(openSocket: ((query: Query<Model>) => Observable<Array<Model>>));
    readonly alreadyOpen: Map<Query<Model>, Observable<Array<Model>>>;
    observe(query: Query<Model>): Observable<Array<Model>>;
}
export declare namespace LiveObserveModelApi {
    class Companion {
        private constructor();
        static INSTANCE: Companion;
        create<Model extends HasId>(Model: Array<any>, multiplexUrl: string, token: string, path: string): LiveObserveModelApi<Model>;
    }
}
export declare function xObservableToListObservable<T extends HasId>(this_: Observable<ListChange<T>>, ordering: Comparator<T>): Observable<Array<T>>;
