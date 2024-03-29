// Package: com.lightningkite.lightningserver.files
// Generated by Khrysalis - this file will be overwritten.
import { HasId } from '../../db/HasId'
import { Duration, Instant } from '@js-joda/core'
import { ReifiedType, setUpDataClass } from '@lightningkite/khrysalis-runtime'
import { v4 as randomUuidV4 } from 'uuid'

//! Declares com.lightningkite.lightningserver.files.UploadForNextRequest
export class UploadForNextRequest implements HasId<string> {
    public static implementsHasId = true;
    public constructor(public readonly _id: string = randomUuidV4(), public readonly file: string, public readonly expires: Instant = Instant.now().plus(Duration.ofMinutes(15))) {
    }
    public static properties = ["_id", "file", "expires"]
    public static propertyTypes() { return {_id: [String], file: [String], expires: [Instant]} }
    copy: (values: Partial<UploadForNextRequest>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
}
setUpDataClass(UploadForNextRequest)

//! Declares com.lightningkite.lightningserver.files.UploadInformation
export class UploadInformation {
    public constructor(public readonly uploadUrl: string, public readonly futureCallToken: string) {
    }
    public static properties = ["uploadUrl", "futureCallToken"]
    public static propertyTypes() { return {uploadUrl: [String], futureCallToken: [String]} }
    copy: (values: Partial<UploadInformation>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
}
setUpDataClass(UploadInformation)