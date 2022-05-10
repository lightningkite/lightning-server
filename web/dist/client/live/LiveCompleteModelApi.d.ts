import { HasId } from '../../shared/HasId';
import { CompleteModelApi } from '../CompleteModelApi';
import { ObserveModelApi } from '../ObserveModelApi';
import { ReadModelApi } from '../ReadModelApi';
import { WriteModelApi } from '../WriteModelApi';
export declare class LiveCompleteModelApi<Model extends HasId> extends CompleteModelApi<Model> {
    readonly read: ReadModelApi<Model>;
    readonly write: WriteModelApi<Model>;
    readonly observe: ObserveModelApi<Model>;
    constructor(read: ReadModelApi<Model>, write: WriteModelApi<Model>, observe: ObserveModelApi<Model>);
}
export declare namespace LiveCompleteModelApi {
    class Companion {
        private constructor();
        static INSTANCE: Companion;
        create<Model extends HasId>(Model: Array<any>, root: string, multiplexSocketUrl: string, path: string, token: string): LiveCompleteModelApi<Model>;
    }
}
