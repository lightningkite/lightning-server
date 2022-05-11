import { Query } from './db/Query';
import { Observable } from 'rxjs';
export declare abstract class ObserveModelApi<Model extends any> {
    protected constructor();
    abstract observe(query: Query<Model>): Observable<Array<Model>>;
}
