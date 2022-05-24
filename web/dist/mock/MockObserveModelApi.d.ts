import { ObserveModelApi } from '../ObserveModelApi';
import { HasId } from '../db/HasId';
import { Query } from '../db/Query';
import { MockTable } from './MockTable';
import { Observable } from 'rxjs';
export declare class MockObserveModelApi<Model extends HasId<string>> extends ObserveModelApi<Model> {
    readonly table: MockTable<Model>;
    constructor(table: MockTable<Model>);
    observe(query: Query<Model>): Observable<Array<Model>>;
}
