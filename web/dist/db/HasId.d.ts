import { DataClassProperty } from './DataClassProperty';
import { Comparable } from '@lightningkite/khrysalis-runtime';
export declare abstract class HasId<ID extends Comparable<ID>> {
    protected constructor();
    abstract readonly _id: ID;
}
export declare class HasIdFields {
    private constructor();
    static INSTANCE: HasIdFields;
    _id<T extends HasId<ID>, ID extends Comparable<ID>>(): DataClassProperty<T, ID>;
}
export interface HasEmail {
    readonly email: string;
}
export declare class HasEmailFields {
    private constructor();
    static INSTANCE: HasEmailFields;
    email<T extends HasEmail>(): DataClassProperty<T, string>;
}
