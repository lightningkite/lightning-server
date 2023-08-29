import { Comparable } from '@lightningkite/khrysalis-runtime';
export interface HasId<ID extends Comparable<ID>> {
    readonly _id: ID;
}
export interface HasEmail {
    readonly email: string;
}
export interface HasPhoneNumber {
    readonly phoneNumber: string;
}
export interface HasMaybeEmail {
    readonly email: (string | null);
}
export interface HasMaybePhoneNumber {
    readonly phoneNumber: (string | null);
}
export interface HasPassword {
    readonly hashedPassword: string;
}
