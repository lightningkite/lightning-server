import { WriteModelApi } from '../WriteModelApi';
import { Condition } from '../db/Condition';
import { HasId } from '../db/HasId';
import { MassModification } from '../db/MassModification';
import { Modification } from '../db/Modification';
import { UUIDFor } from '../db/UUIDFor';
import { ReifiedType } from '@lightningkite/khrysalis-runtime';
import { Observable } from 'rxjs';
export declare class LiveWriteModelApi<Model extends HasId<string>> extends WriteModelApi<Model> {
    readonly url: string;
    readonly serializer: ReifiedType;
    constructor(url: string, token: string, headers: Map<string, string>, serializer: ReifiedType);
    private readonly authHeaders;
    post(value: Model): Observable<Model>;
    postBulk(values: Array<Model>): Observable<Array<Model>>;
    upsert(value: Model, id: UUIDFor<Model>): Observable<Model>;
    put(value: Model): Observable<Model>;
    putBulk(values: Array<Model>): Observable<Array<Model>>;
    patch(id: UUIDFor<Model>, modification: Modification<Model>): Observable<Model>;
    patchBulk(modification: MassModification<Model>): Observable<number>;
    _delete(id: UUIDFor<Model>): Observable<void>;
    deleteBulk(condition: Condition<Model>): Observable<void>;
}
export declare namespace LiveWriteModelApi {
    class Companion {
        private constructor();
        static INSTANCE: Companion;
        create<Model extends HasId<string>>(Model: Array<any>, root: string, path: string, token: string, headers?: Map<string, string>): LiveWriteModelApi<Model>;
    }
}
