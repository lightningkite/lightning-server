import { Comparable, TProperty1 } from '@lightningkite/khrysalis-runtime';
export interface HasId<ID extends Comparable<ID>> {
    readonly _id: ID;
}
export declare class HasIdFields {
    private constructor();
    static INSTANCE: HasIdFields;
    _id<T extends HasId<ID>, ID extends Comparable<ID>>(): TProperty1<T, ID>;
}
export interface HasEmail {
    readonly email: string;
}
export declare class HasEmailFields {
    private constructor();
    static INSTANCE: HasEmailFields;
    email<T extends HasEmail>(): TProperty1<T, string>;
}
export interface HasPhoneNumber {
    readonly phoneNumber: string;
}
export declare class HasPhoneNumberFields {
    private constructor();
    static INSTANCE: HasPhoneNumberFields;
    phoneNumber<T extends HasPhoneNumber>(): TProperty1<T, string>;
}
