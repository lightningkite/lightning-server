// Package: com.lightningkite.lightningdb
// Generated by Khrysalis - this file will be overwritten.
import {ReifiedType, setUpDataClass} from '@lightningkite/khrysalis-runtime'

//! Declares com.lightningkite.lightningdb.MultiplexMessage
export class MultiplexMessage {
    public constructor(public readonly channel: string, public readonly path: (string | null) = null, public readonly queryParams: (Map<string, Array<string>> | null) = null, public readonly start: boolean = false, public readonly end: boolean = false, public readonly data: (string | null) = null, public readonly error: (string | null) = null) {
    }

    public static properties = ["channel", "path", "queryParams", "start", "end", "data", "error"]

    public static propertyTypes() {
        return {
            channel: [String],
            path: [String],
            queryParams: [Map, [String], [Array, [String]]],
            start: [Boolean],
            end: [Boolean],
            data: [String],
            error: [String]
        }
    }

    copy: (values: Partial<MultiplexMessage>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
}

setUpDataClass(MultiplexMessage)