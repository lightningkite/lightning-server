export declare class LSError {
    readonly http: number;
    readonly detail: string;
    readonly message: string;
    readonly data: string;
    constructor(http: number, detail?: string, message?: string, data?: string);
    static properties: string[];
    static propertyTypes(): {
        http: NumberConstructor[];
        detail: StringConstructor[];
        message: StringConstructor[];
        data: StringConstructor[];
    };
    copy: (values: Partial<LSError>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
}
