import { Condition } from './db/Condition';
import { MassModification } from './db/MassModification';
import { Modification } from './db/Modification';
import { UUIDFor } from './db/UUIDFor';
import { Observable } from 'rxjs';
export declare abstract class WriteModelApi<Model extends any> {
    protected constructor();
    abstract post(value: Model): Observable<Model>;
    abstract postBulk(values: Array<Model>): Observable<Array<Model>>;
    abstract put(value: Model): Observable<Model>;
    abstract putBulk(values: Array<Model>): Observable<Array<Model>>;
    abstract patch(id: UUIDFor<Model>, modification: Modification<Model>): Observable<Model>;
    abstract patchBulk(modification: MassModification<Model>): Observable<number>;
    abstract _delete(id: UUIDFor<Model>): Observable<void>;
    abstract deleteBulk(condition: Condition<Model>): Observable<void>;
}
