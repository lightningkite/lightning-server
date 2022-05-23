import { CompleteModelApi } from '../CompleteModelApi';
import { ObserveModelApi } from '../ObserveModelApi';
import { ReadModelApi } from '../ReadModelApi';
import { WriteModelApi } from '../WriteModelApi';
import { HasId } from '../db/HasId';
import { MockTable } from './MockTable';
export declare class MockCompleteModelApi<Model extends HasId<string>> extends CompleteModelApi<Model> {
    readonly table: MockTable<Model>;
    constructor(table: MockTable<Model>);
    readonly read: ReadModelApi<Model>;
    readonly write: WriteModelApi<Model>;
    readonly observe: ObserveModelApi<Model>;
}
