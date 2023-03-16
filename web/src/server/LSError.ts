// Package: com.lightningkite.lightningserver
// Generated by Khrysalis - this file will be overwritten.
import { ReifiedType, setUpDataClass } from '@lightningkite/khrysalis-runtime'

//! Declares com.lightningkite.lightningserver.LSError
export class LSError {
    public constructor(public readonly http: number, public readonly detail: string = "", public readonly message: string = "", public readonly data: string = "") {
    }
    public static properties = ["http", "detail", "message", "data"]
    public static propertyTypes() { return {http: [Number], detail: [String], message: [String], data: [String]} }
    copy: (values: Partial<LSError>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
    
}
setUpDataClass(LSError)