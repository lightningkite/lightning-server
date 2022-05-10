import { ObserveModelApi } from './ObserveModelApi';
import { ReadModelApi } from './ReadModelApi';
import { WriteModelApi } from './WriteModelApi';
export declare abstract class CompleteModelApi<Model extends any> {
    protected constructor();
    abstract readonly read: ReadModelApi<Model>;
    abstract readonly write: WriteModelApi<Model>;
    abstract readonly observe: ObserveModelApi<Model>;
}
