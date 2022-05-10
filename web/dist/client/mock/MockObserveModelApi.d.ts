import { HasId } from '../../shared/HasId';
import { Query } from '../../shared/Query';
import { ObserveModelApi } from '../ObserveModelApi';
import { MockTable } from './MockTable';
import { Observable } from 'rxjs';
export declare class MockObserveModelApi<Model extends HasId> extends ObserveModelApi<Model> {
    readonly table: MockTable<Model>;
    constructor(table: MockTable<Model>);
    observe(query: Query<Model>): Observable<Array<Model>>;
}
