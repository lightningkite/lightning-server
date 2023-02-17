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
export interface HasMaybeEmail {
    readonly email: (string | null);
}
export declare class HasMaybeEmailFields {
    private constructor();
    static INSTANCE: HasMaybeEmailFields;
    email<T extends HasMaybeEmail>(): TProperty1<T, (string | null)>;
}
export interface HasMaybePhoneNumber {
    readonly phoneNumber: (string | null);
}
export declare class HasMaybePhoneNumberFields {
    private constructor();
    static INSTANCE: HasMaybePhoneNumberFields;
    phoneNumber<T extends HasMaybePhoneNumber>(): TProperty1<T, (string | null)>;
}
export interface HasPassword {
    readonly hashedPassword: string;
}
export declare class HasPasswordFields {
    private constructor();
    static INSTANCE: HasPasswordFields;
    hashedPassword<T extends HasPassword>(): TProperty1<T, string>;
}
