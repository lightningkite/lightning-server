// Package: com.lightningkite.lightningdb
// Generated by Khrysalis - this file will be overwritten.
import {Condition} from './Condition'
import {Modification} from './Modification'
import {ReifiedType, setUpDataClass} from '@lightningkite/khrysalis-runtime'

//! Declares com.lightningkite.lightningdb.MassModification
export class MassModification<T extends any> {
    public constructor(public readonly condition: Condition<T>, public readonly modification: Modification<T>) {
    }

    public static properties = ["condition", "modification"]

    public static propertyTypes(T: ReifiedType) {
        return {condition: [Condition, T], modification: [Modification, T]}
    }

    copy: (values: Partial<MassModification<T>>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
}

setUpDataClass(MassModification)
