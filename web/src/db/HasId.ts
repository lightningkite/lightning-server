// Package: com.lightningkite.lightningdb
// Generated by Khrysalis, but customized afterwards
import { DataClassProperty } from './DataClassProperty'
import { Comparable } from '@lightningkite/khrysalis-runtime'

//! Declares com.lightningkite.lightningdb.HasId
export interface HasId<ID extends Comparable<ID>> {

    readonly _id: ID;

}

//! Declares com.lightningkite.lightningdb.HasIdFields
export class HasIdFields {
    private constructor() {
    }
    public static INSTANCE = new HasIdFields();
    
    _id<T extends HasId<ID>, ID extends Comparable<ID>>(): DataClassProperty<T, ID> {
        return "_id" as DataClassProperty<T, ID>;
    }
}

//! Declares com.lightningkite.lightningdb.HasEmail
export interface HasEmail {
    
    readonly email: string;
    
}


//! Declares com.lightningkite.lightningdb.HasEmailFields
export class HasEmailFields {
    private constructor() {
    }
    public static INSTANCE = new HasEmailFields();
    
    email<T extends HasEmail>(): DataClassProperty<T, string> {
        return "email" as DataClassProperty<T, string>;
    }
}
