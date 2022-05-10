import { HasId } from '../../shared/HasId';
import { Query } from '../../shared/Query';
import { UUIDFor } from '../../shared/UUIDFor';
import { ReadModelApi } from '../ReadModelApi';
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
