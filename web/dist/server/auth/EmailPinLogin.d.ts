export declare class EmailPinLogin {
    readonly email: string;
    readonly pin: string;
    constructor(email: string, pin: string);
    static properties: string[];
    static propertyTypes(): {
        email: StringConstructor[];
        pin: StringConstructor[];
    };
    copy: (values: Partial<EmailPinLogin>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
}
export declare class PhonePinLogin {
    readonly phone: string;
    readonly pin: string;
    constructor(phone: string, pin: string);
    static properties: string[];
    static propertyTypes(): {
        phone: StringConstructor[];
        pin: StringConstructor[];
    };
    copy: (values: Partial<PhonePinLogin>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
}
export declare class PasswordLogin {
    readonly username: string;
    readonly password: string;
    constructor(username: string, password: string);
    static properties: string[];
    static propertyTypes(): {
        username: StringConstructor[];
        password: StringConstructor[];
    };
    copy: (values: Partial<PasswordLogin>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
}
