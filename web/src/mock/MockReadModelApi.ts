// Package: com.lightningkite.lightningdb.mock
// Generated by Khrysalis - this file will be overwritten.
import { ReadModelApi } from '../ReadModelApi'
import { HasId } from '../db/HasId'
import { Query } from '../db/Query'
import { xListComparatorGet } from '../db/SortPart'
import { UUIDFor } from '../db/UUIDFor'
import { ItemNotFound } from './ItemNotFound'
import { MockTable } from './MockTable'
import { Comparable, compareBy } from '@lightningkite/khrysalis-runtime'
import { Observable, of, throwError } from 'rxjs'

//! Declares com.lightningkite.lightningdb.mock.MockReadModelApi
export class MockReadModelApi<Model extends HasId<string>> extends ReadModelApi<Model> {
    public constructor(public readonly table: MockTable<Model>) {
        super();
    }
    
    
    public list(query: Query<Model>): Observable<Array<Model>> {
        return of(this.table
                .asList()
                .filter((item: Model): boolean => (query.condition.invoke(item)))
                .slice().sort(xListComparatorGet(query.orderBy) ?? compareBy<Model>((it: Model): (Comparable<(any | null)> | null) => (it._id)))
                .slice(query.skip)
            .slice(0, query.limit));
    }
    
    public get(id: UUIDFor<Model>): Observable<Model> {
        return ((): (Observable<Model> | null) => {
            const temp9 = this.table.getItem(id);
            if (temp9 === null || temp9 === undefined) { return null }
            return ((it: Model): Observable<Model> => (of(it)))(temp9)
        })() ?? throwError(new ItemNotFound(`404 item with key ${id} not found`));
    }
}

