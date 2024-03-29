// Package: com.lightningkite.lightningdb.live
// Generated by Khrysalis - this file will be overwritten.
import { WriteModelApi } from '../WriteModelApi'
import { Condition } from '../db/Condition'
import { HasId } from '../db/HasId'
import { MassModification } from '../db/MassModification'
import { Modification } from '../db/Modification'
import { UUIDFor } from '../db/UUIDFor'
import { ReifiedType } from '@lightningkite/khrysalis-runtime'
import { HttpBody, HttpClient, fromJSON, unsuccessfulAsError } from '@lightningkite/rxjs-plus'
import { Observable, from, map as rMap, switchMap } from 'rxjs'
import { map, mergeMap } from 'rxjs/operators'

//! Declares com.lightningkite.lightningdb.live.LiveWriteModelApi
export class LiveWriteModelApi<Model extends HasId<string>> extends WriteModelApi<Model> {
    public constructor(public readonly url: string, token: string, headers: Map<string, string>, public readonly serializer: ReifiedType) {
        super();
        this.authHeaders = new Map([...headers, ...new Map([["Authorization", `Bearer ${token}`]])]);
    }
    
    
    
    
    private readonly authHeaders: Map<string, string>;
    
    public post(value: Model): Observable<Model> {
        return HttpClient.INSTANCE.call(this.url, HttpClient.INSTANCE.POST, this.authHeaders, HttpBody.json(value), undefined).pipe(unsuccessfulAsError, fromJSON<Model>(this.serializer));
    }
    
    public postBulk(values: Array<Model>): Observable<Array<Model>> {
        return HttpClient.INSTANCE.call(`${this.url}/bulk`, HttpClient.INSTANCE.POST, this.authHeaders, HttpBody.json(values), undefined).pipe(unsuccessfulAsError, fromJSON<Array<Model>>([Array, this.serializer]));
    }
    
    public upsert(value: Model, id: UUIDFor<Model>): Observable<Model> {
        return HttpClient.INSTANCE.call(`${this.url}/${value._id}`, HttpClient.INSTANCE.POST, this.authHeaders, HttpBody.json(value), undefined).pipe(unsuccessfulAsError, fromJSON<Model>(this.serializer));
    }
    
    public put(value: Model): Observable<Model> {
        return HttpClient.INSTANCE.call(`${this.url}/${value._id}`, HttpClient.INSTANCE.PUT, this.authHeaders, HttpBody.json(value), undefined).pipe(unsuccessfulAsError, fromJSON<Model>(this.serializer));
    }
    
    public putBulk(values: Array<Model>): Observable<Array<Model>> {
        return HttpClient.INSTANCE.call(`${this.url}/bulk`, HttpClient.INSTANCE.PUT, this.authHeaders, HttpBody.json(values), undefined).pipe(unsuccessfulAsError, fromJSON<Array<Model>>([Array, this.serializer]));
    }
    
    public patch(id: UUIDFor<Model>, modification: Modification<Model>): Observable<Model> {
        return HttpClient.INSTANCE.call(`${this.url}/${id}`, HttpClient.INSTANCE.PATCH, this.authHeaders, HttpBody.json(modification), undefined).pipe(unsuccessfulAsError, fromJSON<Model>(this.serializer));
    }
    
    public patchBulk(modification: MassModification<Model>): Observable<number> {
        return HttpClient.INSTANCE.call(`${this.url}/bulk`, HttpClient.INSTANCE.PATCH, this.authHeaders, HttpBody.json(modification), undefined)
            .pipe(mergeMap((it: Response): Observable<string> => (from(it.text()))))
            .pipe(map((it: string): number => (parseInt(it))));
    }
    
    public _delete(id: UUIDFor<Model>): Observable<void> {
        return HttpClient.INSTANCE.call(`${this.url}/${id}`, HttpClient.INSTANCE.DELETE, this.authHeaders, undefined, undefined).pipe(unsuccessfulAsError, switchMap(x => x.text().then(x => undefined)));
    }
    
    public deleteBulk(condition: Condition<Model>): Observable<void> {
        return HttpClient.INSTANCE.call(`${this.url}/bulk`, HttpClient.INSTANCE.DELETE, this.authHeaders, HttpBody.json(condition), undefined).pipe(unsuccessfulAsError, switchMap(x => x.text().then(x => undefined)));
    }
}
export namespace LiveWriteModelApi {
    //! Declares com.lightningkite.lightningdb.live.LiveWriteModelApi.Companion
    export class Companion {
        private constructor() {
        }
        public static INSTANCE = new Companion();
        
        public create<Model extends HasId<string>>(Model: Array<any>, root: string, path: string, token: string, headers: Map<string, string> = new Map([])): LiveWriteModelApi<Model> {
            return new LiveWriteModelApi<Model>(`${root}${path}`, token, headers, Model);
        }
    }
}