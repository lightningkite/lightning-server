import { HasId } from '../../shared/HasId';
import { Query } from '../../shared/Query';
import { UUIDFor } from '../../shared/UUIDFor';
import { ReadModelApi } from '../ReadModelApi';
import { MockTable } from './MockTable';
import { Observable } from 'rxjs';
export declare class MockReadModelApi<Model extends HasId> extends ReadModelApi<Model> {
    readonly table: MockTable<Model>;
    constructor(table: MockTable<Model>);
    list(query: Query<Model>): Observable<Array<Model>>;
    get(id: UUIDFor<Model>): Observable<Model>;
}
