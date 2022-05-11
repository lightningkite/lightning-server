// Package: com.lightningkite.ktordb
// Generated by Khrysalis - this file will be overwritten.
import { Query } from './db/Query'
import { UUIDFor } from './db/UUIDFor'
import { Observable } from 'rxjs'

//! Declares com.lightningkite.ktordb.ReadModelApi
export abstract class ReadModelApi<Model extends any> {
    protected constructor() {
    }
    
    public abstract list(query: Query<Model>): Observable<Array<Model>>
    public abstract get(id: UUIDFor<Model>): Observable<Model>
}