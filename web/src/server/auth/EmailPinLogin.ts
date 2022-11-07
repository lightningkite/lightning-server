// Package: com.lightningkite.lightningserver.auth
// Generated by Khrysalis - this file will be overwritten.
import { ReifiedType, setUpDataClass } from '@lightningkite/khrysalis-runtime'

//! Declares com.lightningkite.lightningserver.auth.EmailPinLogin
export class EmailPinLogin {
    public constructor(public readonly email: string, public readonly pin: string) {
    }
    public static properties = ["email", "pin"]
    public static propertyTypes() { return {email: [String], pin: [String]} }
    copy: (values: Partial<EmailPinLogin>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
    
}
setUpDataClass(EmailPinLogin)

//! Declares com.lightningkite.lightningserver.auth.PhonePinLogin
export class PhonePinLogin {
    public constructor(public readonly phone: string, public readonly pin: string) {
    }
    public static properties = ["phone", "pin"]
    public static propertyTypes() { return {phone: [String], pin: [String]} }
    copy: (values: Partial<PhonePinLogin>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
    
}
setUpDataClass(PhonePinLogin)