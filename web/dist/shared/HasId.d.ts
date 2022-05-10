import { DataClassProperty } from './DataClassProperty';
export interface HasId {
    readonly _id: string;
}
export declare class HasIdFields {
    private constructor();
    static INSTANCE: HasIdFields;
    _id<T extends HasId>(): DataClassProperty<T, string>;
}
