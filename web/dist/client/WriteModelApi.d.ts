import { Condition } from '../shared/Condition';
import { MassModification } from '../shared/MassModification';
import { Modification } from '../shared/Modification';
import { UUIDFor } from '../shared/UUIDFor';
import { Observable } from 'rxjs';
export declare abstract class WriteModelApi<Model extends any> {
    protected constructor();
    abstract post(value: Model): Observable<Model>;
    abstract postBulk(values: Array<Model>): Observable<Array<Model>>;
    abstract put(value: Model): Observable<Model>;
    abstract putBulk(values: Array<Model>): Observable<Array<Model>>;
    abstract patch(id: UUIDFor<Model>, modification: Modification<Model>): Observable<Model>;
    abstract patchBulk(modification: MassModification<Model>): Observable<Array<Model>>;
    abstract _delete(id: UUIDFor<Model>): Observable<void>;
    abstract deleteBulk(condition: Condition<Model>): Observable<void>;
}
