import { FullReadModelApi } from '../FullReadModelApi';
import { ObserveModelApi } from '../ObserveModelApi';
import { HasId } from '../db/HasId';
import { LiveReadModelApi } from './LiveReadModelApi';
export declare class LiveFullReadModelApi<Model extends HasId> extends FullReadModelApi<Model> {
    readonly read: LiveReadModelApi<Model>;
    readonly observe: ObserveModelApi<Model>;
    constructor(read: LiveReadModelApi<Model>, observe: ObserveModelApi<Model>);
}
export declare namespace LiveFullReadModelApi {
    class Companion {
        private constructor();
        static INSTANCE: Companion;
        create<Model extends HasId>(Model: Array<any>, root: string, multiplexSocketUrl: string, path: string, token: string, headers?: Map<string, string>): LiveFullReadModelApi<Model>;
    }
}
