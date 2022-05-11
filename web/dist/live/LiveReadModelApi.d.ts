import { ReadModelApi } from '../ReadModelApi';
import { HasId } from '../db/HasId';
import { Query } from '../db/Query';
import { UUIDFor } from '../db/UUIDFor';
import { ReifiedType } from '@lightningkite/khrysalis-runtime';
import { Observable } from 'rxjs';
export declare class LiveReadModelApi<Model extends HasId> extends ReadModelApi<Model> {
    readonly url: string;
    readonly serializer: ReifiedType;
    constructor(url: string, token: string, headers: Map<string, string> | undefined, serializer: ReifiedType);
    private readonly authHeaders;
    list(query: Query<Model>): Observable<Array<Model>>;
    get(id: UUIDFor<Model>): Observable<Model>;
}
export declare namespace LiveReadModelApi {
    class Companion {
        private constructor();
        static INSTANCE: Companion;
        create<Model extends HasId>(Model: Array<any>, root: string, path: string, token: string, headers?: Map<string, string>): LiveReadModelApi<Model>;
    }
}
