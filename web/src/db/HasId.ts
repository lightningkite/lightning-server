// Package: com.lightningkite.ktordb
// Generated by Khrysalis, but customized afterwards
import { DataClassProperty } from './DataClassProperty'
import { Comparable } from '@lightningkite/khrysalis-runtime'

//! Declares com.lightningkite.ktordb.HasId
export abstract class HasId<ID extends Comparable<ID>> {
    protected constructor() {
    }
    
    public abstract readonly _id: ID;
}

//! Declares com.lightningkite.ktordb.HasIdFields
export class HasIdFields {
    private constructor() {
    }
    public static INSTANCE = new HasIdFields();
    
    _id<T extends HasId<ID>, ID extends Comparable<ID>>(): DataClassProperty<T, ID> {
        return "_id" as DataClassProperty<T, ID>;
    }
}

//! Declares com.lightningkite.ktordb.HasEmail
export interface HasEmail {
    
    readonly email: string;
    
}


//! Declares com.lightningkite.ktordb.HasEmailFields
export class HasEmailFields {
    private constructor() {
    }
    public static INSTANCE = new HasEmailFields();
    
    email<T extends HasEmail>(): DataClassProperty<T, string> {
        return "email" as DataClassProperty<T, string>;
    }
}
