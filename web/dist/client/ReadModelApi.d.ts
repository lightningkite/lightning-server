import { Query } from '../shared/Query';
import { UUIDFor } from '../shared/UUIDFor';
import { Observable } from 'rxjs';
export declare abstract class ReadModelApi<Model extends any> {
    protected constructor();
    abstract list(query: Query<Model>): Observable<Array<Model>>;
    abstract get(id: UUIDFor<Model>): Observable<Model>;
}
