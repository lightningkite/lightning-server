import { HasId } from '../HasId';
import { ObserveModelApi } from '../ObserveModelApi';
import { Query } from '../Query';
import { MockTable } from './MockTable';
import { Observable } from 'rxjs';
export declare class MockObserveModelApi<Model extends HasId> extends ObserveModelApi<Model> {
    readonly table: MockTable<Model>;
    constructor(table: MockTable<Model>);
    observe(query: Query<Model>): Observable<Array<Model>>;
}
