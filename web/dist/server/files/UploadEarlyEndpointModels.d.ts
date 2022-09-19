import { HasId } from '../../db/HasId';
import { Instant } from '@js-joda/core';
export declare class UploadForNextRequest implements HasId<string> {
    readonly _id: string;
    readonly file: string;
    readonly expires: Instant;
    static implementsHasId: boolean;
    constructor(_id: string, file: string, expires?: Instant);
    static properties: string[];
    static propertyTypes(): {
        _id: StringConstructor[];
        file: StringConstructor[];
        expires: (typeof Instant)[];
    };
    copy: (values: Partial<UploadForNextRequest>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
}
export declare class UploadInformation {
    readonly uploadUrl: string;
    readonly futureCallToken: string;
    constructor(uploadUrl: string, futureCallToken: string);
    static properties: string[];
    static propertyTypes(): {
        uploadUrl: StringConstructor[];
        futureCallToken: StringConstructor[];
    };
    copy: (values: Partial<UploadInformation>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
}
