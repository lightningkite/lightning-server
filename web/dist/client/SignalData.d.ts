import { HasId } from '../shared/HasId';
import { ReifiedType } from '@lightningkite/khrysalis-runtime';
export declare class SignalData<Model extends HasId> {
    readonly item: Model;
    readonly created: boolean;
    readonly deleted: boolean;
    constructor(item: Model, created: boolean, deleted: boolean);
    static properties: string[];
    static propertyTypes(Model: ReifiedType): {
        item: ReifiedType<unknown>;
        created: BooleanConstructor[];
        deleted: BooleanConstructor[];
    };
    copy: (values: Partial<SignalData<Model>>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
}
