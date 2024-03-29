// Package: com.lightningkite.lightningdb.live
// Generated by Khrysalis - this file will be overwritten.
import { CompleteModelApi } from '../CompleteModelApi'
import { ObserveModelApi } from '../ObserveModelApi'
import { ReadModelApi } from '../ReadModelApi'
import { WriteModelApi } from '../WriteModelApi'
import { HasId } from '../db/HasId'
import { LiveObserveModelApi } from './LiveObserveModelApi'
import { LiveReadModelApi } from './LiveReadModelApi'
import { LiveWriteModelApi } from './LiveWriteModelApi'

//! Declares com.lightningkite.lightningdb.live.LiveCompleteModelApi
export class LiveCompleteModelApi<Model extends HasId<string>> extends CompleteModelApi<Model> {
    public constructor(public readonly read: ReadModelApi<Model>, public readonly write: WriteModelApi<Model>, public readonly observe: ObserveModelApi<Model>) {
        super();
    }
    
    
}
export namespace LiveCompleteModelApi {
    //! Declares com.lightningkite.lightningdb.live.LiveCompleteModelApi.Companion
    export class Companion {
        private constructor() {
        }
        public static INSTANCE = new Companion();
        
        public create<Model extends HasId<string>>(Model: Array<any>, root: string, multiplexSocketUrl: string, path: string, token: string, headers: Map<string, string> = new Map([])): LiveCompleteModelApi<Model> {
            return new LiveCompleteModelApi<Model>(LiveReadModelApi.Companion.INSTANCE.create<Model>(Model, root, path, token, headers), LiveWriteModelApi.Companion.INSTANCE.create<Model>(Model, root, path, token, headers), LiveObserveModelApi.Companion.INSTANCE.create<Model>(Model, multiplexSocketUrl, token, headers, path));
        }
    }
}