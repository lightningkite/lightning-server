export declare class MultiplexMessage {
    readonly channel: string;
    readonly path: (string | null);
    readonly start: boolean;
    readonly end: boolean;
    readonly data: (string | null);
    readonly error: (string | null);
    constructor(channel: string, path?: (string | null), start?: boolean, end?: boolean, data?: (string | null), error?: (string | null));
    static properties: string[];
    static propertyTypes(): {
        channel: StringConstructor[];
        path: StringConstructor[];
        start: BooleanConstructor[];
        end: BooleanConstructor[];
        data: StringConstructor[];
        error: StringConstructor[];
    };
    copy: (values: Partial<MultiplexMessage>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
}
