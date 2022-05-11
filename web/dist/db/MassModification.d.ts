import { Condition } from './Condition';
import { Modification } from './Modification';
import { ReifiedType } from '@lightningkite/khrysalis-runtime';
export declare class MassModification<T extends any> {
    readonly condition: Condition<T>;
    readonly modification: Modification<T>;
    constructor(condition: Condition<T>, modification: Modification<T>);
    static properties: string[];
    static propertyTypes(T: ReifiedType): {
        condition: (ReifiedType<unknown> | typeof Condition)[];
        modification: (ReifiedType<unknown> | typeof Modification)[];
    };
    copy: (values: Partial<MassModification<T>>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
}
