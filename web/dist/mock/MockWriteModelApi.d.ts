import { Condition } from '../Condition';
import { HasId } from '../HasId';
import { MassModification } from '../MassModification';
import { Modification } from '../Modification';
import { UUIDFor } from '../UUIDFor';
import { WriteModelApi } from '../WriteModelApi';
import { MockTable } from './MockTable';
import { Observable } from 'rxjs';
export declare class MockWriteModelApi<Model extends HasId> extends WriteModelApi<Model> {
    readonly table: MockTable<Model>;
    constructor(table: MockTable<Model>);
    post(value: Model): Observable<Model>;
    postBulk(values: Array<Model>): Observable<Array<Model>>;
    put(value: Model): Observable<Model>;
    putBulk(values: Array<Model>): Observable<Array<Model>>;
    patch(id: UUIDFor<Model>, modification: Modification<Model>): Observable<Model>;
    patchBulk(modification: MassModification<Model>): Observable<Array<Model>>;
    _delete(id: UUIDFor<Model>): Observable<void>;
    deleteBulk(condition: Condition<Model>): Observable<void>;
}
