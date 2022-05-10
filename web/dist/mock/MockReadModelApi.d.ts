import { HasId } from '../HasId';
import { Query } from '../Query';
import { ReadModelApi } from '../ReadModelApi';
import { UUIDFor } from '../UUIDFor';
import { MockTable } from './MockTable';
import { Observable } from 'rxjs';
export declare class MockReadModelApi<Model extends HasId> extends ReadModelApi<Model> {
    readonly table: MockTable<Model>;
    constructor(table: MockTable<Model>);
    list(query: Query<Model>): Observable<Array<Model>>;
    get(id: UUIDFor<Model>): Observable<Model>;
}
