import { ObserveModelApi } from './ObserveModelApi';
import { ReadModelApi } from './ReadModelApi';
export declare abstract class FullReadModelApi<Model extends any> {
    protected constructor();
    abstract readonly read: ReadModelApi<Model>;
    abstract readonly observe: ObserveModelApi<Model>;
}
