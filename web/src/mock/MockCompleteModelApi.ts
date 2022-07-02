// Package: com.lightningkite.lightningdb.mock
// Generated by Khrysalis - this file will be overwritten.
import { CompleteModelApi } from '../CompleteModelApi'
import { ObserveModelApi } from '../ObserveModelApi'
import { ReadModelApi } from '../ReadModelApi'
import { WriteModelApi } from '../WriteModelApi'
import { HasId } from '../db/HasId'
import { MockObserveModelApi } from './MockObserveModelApi'
import { MockReadModelApi } from './MockReadModelApi'
import { MockTable } from './MockTable'
import { MockWriteModelApi } from './MockWriteModelApi'

//! Declares com.lightningkite.lightningdb.mock.MockCompleteModelApi
export class MockCompleteModelApi<Model extends HasId<string>> extends CompleteModelApi<Model> {
    public constructor(public readonly table: MockTable<Model>) {
        super();
        this.read = new MockReadModelApi<Model>(this.table);
        this.write = new MockWriteModelApi<Model>(this.table);
        this.observe = new MockObserveModelApi<Model>(this.table);
    }
    
    public readonly read: ReadModelApi<Model>;
    public readonly write: WriteModelApi<Model>;
    public readonly observe: ObserveModelApi<Model>;
}