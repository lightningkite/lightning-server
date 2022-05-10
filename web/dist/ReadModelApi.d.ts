import { Query } from './Query';
import { UUIDFor } from './UUIDFor';
import { Observable } from 'rxjs';
export declare abstract class ReadModelApi<Model extends any> {
    protected constructor();
    abstract list(query: Query<Model>): Observable<Array<Model>>;
    abstract get(id: UUIDFor<Model>): Observable<Model>;
}
