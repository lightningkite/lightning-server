import { HasId } from '../../shared/HasId';
import { FullReadModelApi } from '../FullReadModelApi';
import { ObserveModelApi } from '../ObserveModelApi';
import { ReadModelApi } from '../ReadModelApi';
import { MockTable } from './MockTable';
export declare class MockFullReadModelApi<Model extends HasId> extends FullReadModelApi<Model> {
    readonly table: MockTable<Model>;
    constructor(table: MockTable<Model>);
    readonly read: ReadModelApi<Model>;
    readonly observe: ObserveModelApi<Model>;
}
