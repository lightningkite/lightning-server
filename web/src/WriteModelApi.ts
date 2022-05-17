// Package: com.lightningkite.ktordb
// Generated by Khrysalis - this file will be overwritten.
import { Condition } from './db/Condition'
import { MassModification } from './db/MassModification'
import { Modification } from './db/Modification'
import { UUIDFor } from './db/UUIDFor'
import { Observable } from 'rxjs'

//! Declares com.lightningkite.ktordb.WriteModelApi
export abstract class WriteModelApi<Model extends any> {
    protected constructor() {
    }
    
    public abstract post(value: Model): Observable<Model>
    public abstract postBulk(values: Array<Model>): Observable<Array<Model>>
    public abstract put(value: Model): Observable<Model>
    public abstract putBulk(values: Array<Model>): Observable<Array<Model>>
    public abstract patch(id: UUIDFor<Model>, modification: Modification<Model>): Observable<Model>
    public abstract patchBulk(modification: MassModification<Model>): Observable<number>
    public abstract _delete(id: UUIDFor<Model>): Observable<void>
    public abstract deleteBulk(condition: Condition<Model>): Observable<void>
}