import { DataClassProperty } from './DataClassProperty';
import { ReifiedType } from '@lightningkite/khrysalis-runtime';
export declare class Condition<T extends any> {
    protected constructor();
    hashCode(): number;
    equals(other: (any | null)): boolean;
    invoke(on: T): boolean;
    simplify(): Condition<T>;
    and(other: Condition<T>): Condition.And<T>;
    or(other: Condition<T>): Condition.Or<T>;
    not(): Condition.Not<T>;
}
export declare namespace Condition {
    class Never<T extends any> extends Condition<T> {
        constructor();
        invoke(on: T): boolean;
        hashCode(): number;
        equals(other: (any | null)): boolean;
    }
}
export declare namespace Condition {
    class Always<T extends any> extends Condition<T> {
        constructor();
        invoke(on: T): boolean;
        hashCode(): number;
        equals(other: (any | null)): boolean;
    }
}
export declare namespace Condition {
    class And<T extends any> extends Condition<T> {
        readonly conditions: Array<Condition<T>>;
        constructor(conditions: Array<Condition<T>>);
        static properties: string[];
        static propertyTypes(T: ReifiedType): {
            conditions: (ArrayConstructor | (ReifiedType<unknown> | typeof Condition)[])[];
        };
        copy: (values: Partial<And<T>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: T): boolean;
        simplify(): Condition<T>;
    }
}
export declare namespace Condition {
    class Or<T extends any> extends Condition<T> {
        readonly conditions: Array<Condition<T>>;
        constructor(conditions: Array<Condition<T>>);
        static properties: string[];
        static propertyTypes(T: ReifiedType): {
            conditions: (ArrayConstructor | (ReifiedType<unknown> | typeof Condition)[])[];
        };
        copy: (values: Partial<Or<T>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: T): boolean;
        simplify(): Condition<T>;
    }
}
export declare namespace Condition {
    class Not<T extends any> extends Condition<T> {
        readonly condition: Condition<T>;
        constructor(condition: Condition<T>);
        static properties: string[];
        static propertyTypes(T: ReifiedType): {
            condition: (ReifiedType<unknown> | typeof Condition)[];
        };
        copy: (values: Partial<Not<T>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: T): boolean;
        simplify(): Condition<T>;
    }
}
export declare namespace Condition {
    class Equal<T extends any> extends Condition<T> {
        readonly value: T;
        constructor(value: T);
        static properties: string[];
        static propertyTypes(T: ReifiedType): {
            value: ReifiedType<unknown>;
        };
        copy: (values: Partial<Equal<T>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: T): boolean;
    }
}
export declare namespace Condition {
    class NotEqual<T extends any> extends Condition<T> {
        readonly value: T;
        constructor(value: T);
        static properties: string[];
        static propertyTypes(T: ReifiedType): {
            value: ReifiedType<unknown>;
        };
        copy: (values: Partial<NotEqual<T>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: T): boolean;
    }
}
export declare namespace Condition {
    class Inside<T extends any> extends Condition<T> {
        readonly values: Array<T>;
        constructor(values: Array<T>);
        static properties: string[];
        static propertyTypes(T: ReifiedType): {
            values: (ArrayConstructor | ReifiedType<unknown>)[];
        };
        copy: (values: Partial<Inside<T>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: T): boolean;
    }
}
export declare namespace Condition {
    class NotInside<T extends any> extends Condition<T> {
        readonly values: Array<T>;
        constructor(values: Array<T>);
        static properties: string[];
        static propertyTypes(T: ReifiedType): {
            values: (ArrayConstructor | ReifiedType<unknown>)[];
        };
        copy: (values: Partial<NotInside<T>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: T): boolean;
    }
}
export declare namespace Condition {
    class GreaterThan<T extends any> extends Condition<T> {
        readonly value: T;
        constructor(value: T);
        static properties: string[];
        static propertyTypes(T: ReifiedType): {
            value: ReifiedType<unknown>;
        };
        copy: (values: Partial<GreaterThan<T>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: T): boolean;
    }
}
export declare namespace Condition {
    class LessThan<T extends any> extends Condition<T> {
        readonly value: T;
        constructor(value: T);
        static properties: string[];
        static propertyTypes(T: ReifiedType): {
            value: ReifiedType<unknown>;
        };
        copy: (values: Partial<LessThan<T>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: T): boolean;
    }
}
export declare namespace Condition {
    class GreaterThanOrEqual<T extends any> extends Condition<T> {
        readonly value: T;
        constructor(value: T);
        static properties: string[];
        static propertyTypes(T: ReifiedType): {
            value: ReifiedType<unknown>;
        };
        copy: (values: Partial<GreaterThanOrEqual<T>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: T): boolean;
    }
}
export declare namespace Condition {
    class LessThanOrEqual<T extends any> extends Condition<T> {
        readonly value: T;
        constructor(value: T);
        static properties: string[];
        static propertyTypes(T: ReifiedType): {
            value: ReifiedType<unknown>;
        };
        copy: (values: Partial<LessThanOrEqual<T>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: T): boolean;
    }
}
export declare namespace Condition {
    class Search extends Condition<string> {
        readonly value: string;
        readonly ignoreCase: boolean;
        constructor(value: string, ignoreCase: boolean);
        static properties: string[];
        static propertyTypes(): {
            value: StringConstructor[];
            ignoreCase: BooleanConstructor[];
        };
        copy: (values: Partial<Search>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: string): boolean;
    }
}
export declare namespace Condition {
    class IntBitsClear extends Condition<number> {
        readonly mask: number;
        constructor(mask: number);
        static properties: string[];
        static propertyTypes(): {
            mask: NumberConstructor[];
        };
        copy: (values: Partial<IntBitsClear>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: number): boolean;
    }
}
export declare namespace Condition {
    class IntBitsSet extends Condition<number> {
        readonly mask: number;
        constructor(mask: number);
        static properties: string[];
        static propertyTypes(): {
            mask: NumberConstructor[];
        };
        copy: (values: Partial<IntBitsSet>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: number): boolean;
    }
}
export declare namespace Condition {
    class IntBitsAnyClear extends Condition<number> {
        readonly mask: number;
        constructor(mask: number);
        static properties: string[];
        static propertyTypes(): {
            mask: NumberConstructor[];
        };
        copy: (values: Partial<IntBitsAnyClear>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: number): boolean;
    }
}
export declare namespace Condition {
    class IntBitsAnySet extends Condition<number> {
        readonly mask: number;
        constructor(mask: number);
        static properties: string[];
        static propertyTypes(): {
            mask: NumberConstructor[];
        };
        copy: (values: Partial<IntBitsAnySet>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: number): boolean;
    }
}
export declare namespace Condition {
    class AllElements<E extends any> extends Condition<Array<E>> {
        readonly condition: Condition<E>;
        constructor(condition: Condition<E>);
        static properties: string[];
        static propertyTypes(E: ReifiedType): {
            condition: (ReifiedType<unknown> | typeof Condition)[];
        };
        copy: (values: Partial<AllElements<E>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: Array<E>): boolean;
    }
}
export declare namespace Condition {
    class AnyElements<E extends any> extends Condition<Array<E>> {
        readonly condition: Condition<E>;
        constructor(condition: Condition<E>);
        static properties: string[];
        static propertyTypes(E: ReifiedType): {
            condition: (ReifiedType<unknown> | typeof Condition)[];
        };
        copy: (values: Partial<AnyElements<E>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: Array<E>): boolean;
    }
}
export declare namespace Condition {
    class SizesEquals<E extends any> extends Condition<Array<E>> {
        readonly count: number;
        constructor(count: number);
        static properties: string[];
        static propertyTypes(E: ReifiedType): {
            count: NumberConstructor[];
        };
        copy: (values: Partial<SizesEquals<E>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: Array<E>): boolean;
    }
}
export declare namespace Condition {
    class Exists<V extends any> extends Condition<Map<string, V>> {
        readonly key: string;
        constructor(key: string);
        static properties: string[];
        static propertyTypes(V: ReifiedType): {
            key: StringConstructor[];
        };
        copy: (values: Partial<Exists<V>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: Map<string, V>): boolean;
    }
}
export declare namespace Condition {
    class OnKey<V extends any> extends Condition<Map<string, V>> {
        readonly key: string;
        readonly condition: Condition<V>;
        constructor(key: string, condition: Condition<V>);
        static properties: string[];
        static propertyTypes(V: ReifiedType): {
            key: StringConstructor[];
            condition: (ReifiedType<unknown> | typeof Condition)[];
        };
        copy: (values: Partial<OnKey<V>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: Map<string, V>): boolean;
    }
}
export declare namespace Condition {
    class OnField<K extends any, V extends any> extends Condition<K> {
        readonly key: DataClassProperty<K, V>;
        readonly condition: Condition<V>;
        constructor(key: DataClassProperty<K, V>, condition: Condition<V>);
        static properties: string[];
        static propertyTypes(K: ReifiedType, V: ReifiedType): {
            key: (StringConstructor | ReifiedType<unknown>)[];
            condition: (ReifiedType<unknown> | typeof Condition)[];
        };
        copy: (values: Partial<OnField<K, V>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: K): boolean;
    }
}
export declare namespace Condition {
    class IfNotNull<T extends any> extends Condition<(T | null)> {
        readonly condition: Condition<T>;
        constructor(condition: Condition<T>);
        static properties: string[];
        static propertyTypes(T: ReifiedType): {
            condition: (ReifiedType<unknown> | typeof Condition)[];
        };
        copy: (values: Partial<IfNotNull<T>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: (T | null)): boolean;
    }
}
