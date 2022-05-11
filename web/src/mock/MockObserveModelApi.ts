// Package: com.lightningkite.ktordb.mock
// Generated by Khrysalis - this file will be overwritten.
import { ObserveModelApi } from '../ObserveModelApi'
import { HasId } from '../db/HasId'
import { Query } from '../db/Query'
import { MockTable } from './MockTable'
import { Observable, concat, of } from 'rxjs'

//! Declares com.lightningkite.ktordb.mock.MockObserveModelApi
export class MockObserveModelApi<Model extends HasId> extends ObserveModelApi<Model> {
    public constructor(public readonly table: MockTable<Model>) {
        super();
    }
    
    public observe(query: Query<Model>): Observable<Array<Model>> {
        return concat(of(this.table.asList().filter((item: Model): boolean => (query.condition.invoke(item)))), this.table.observe(query.condition));
    }
}