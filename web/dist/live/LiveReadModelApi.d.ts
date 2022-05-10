import { HasId } from '../HasId';
import { Query } from '../Query';
import { ReadModelApi } from '../ReadModelApi';
import { UUIDFor } from '../UUIDFor';
import { ReifiedType } from '@lightningkite/khrysalis-runtime';
import { Observable } from 'rxjs';
export declare class LiveReadModelApi<Model extends HasId> extends ReadModelApi<Model> {
    readonly url: string;
    readonly token: string;
    readonly serializer: ReifiedType;
    constructor(url: string, token: string, serializer: ReifiedType);
    list(query: Query<Model>): Observable<Array<Model>>;
    get(id: UUIDFor<Model>): Observable<Model>;
}
