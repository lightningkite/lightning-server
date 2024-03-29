// Package: com.lightningkite.lightningdb
// Generated by Khrysalis - this file will be overwritten.
import { Comparable } from '@lightningkite/khrysalis-runtime'

//! Declares com.lightningkite.lightningdb.HasId
export interface HasId<ID extends Comparable<ID>> {
    
    readonly _id: ID;
    
}


//! Declares com.lightningkite.lightningdb.HasEmail
export interface HasEmail {
    
    readonly email: string;
    
}


//! Declares com.lightningkite.lightningdb.HasPhoneNumber
export interface HasPhoneNumber {
    
    readonly phoneNumber: string;
    
}


//! Declares com.lightningkite.lightningdb.HasMaybeEmail
export interface HasMaybeEmail {
    
    readonly email: (string | null);
    
}


//! Declares com.lightningkite.lightningdb.HasMaybePhoneNumber
export interface HasMaybePhoneNumber {
    
    readonly phoneNumber: (string | null);
    
}


//! Declares com.lightningkite.lightningdb.HasPassword
export interface HasPassword {
    
    readonly hashedPassword: string;
    
}


