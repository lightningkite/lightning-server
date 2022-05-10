import { Condition } from './Condition';
import { DataClassProperty } from './DataClassProperty';
import { ReifiedType } from '@lightningkite/khrysalis-runtime';
export declare class Modification<T extends any> {
    protected constructor();
    hashCode(): number;
    equals(other: (any | null)): boolean;
    invoke(on: T): T;
    invokeDefault(): T;
    then(other: Modification<T>): Modification.Chain<T>;
}
export declare namespace Modification {
    class Chain<T extends any> extends Modification<T> {
        readonly modifications: Array<Modification<T>>;
        constructor(modifications: Array<Modification<T>>);
        static properties: string[];
        static propertyTypes(T: ReifiedType): {
            modifications: (ArrayConstructor | (ReifiedType<unknown> | typeof Modification)[])[];
        };
        copy: (values: Partial<Chain<T>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: T): T;
        invokeDefault(): T;
    }
}
export declare namespace Modification {
    class IfNotNull<T extends any> extends Modification<(T | null)> {
        readonly modification: Modification<T>;
        constructor(modification: Modification<T>);
        static properties: string[];
        static propertyTypes(T: ReifiedType): {
            modification: (ReifiedType<unknown> | typeof Modification)[];
        };
        copy: (values: Partial<IfNotNull<T>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: (T | null)): (T | null);
        invokeDefault(): (T | null);
    }
}
export declare namespace Modification {
    class Assign<T extends any> extends Modification<T> {
        readonly value: T;
        constructor(value: T);
        static properties: string[];
        static propertyTypes(T: ReifiedType): {
            value: ReifiedType<unknown>;
        };
        copy: (values: Partial<Assign<T>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: T): T;
        invokeDefault(): T;
    }
}
export declare namespace Modification {
    class CoerceAtMost<T extends any> extends Modification<T> {
        readonly value: T;
        constructor(value: T);
        static properties: string[];
        static propertyTypes(T: ReifiedType): {
            value: ReifiedType<unknown>;
        };
        copy: (values: Partial<CoerceAtMost<T>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: T): T;
        invokeDefault(): T;
    }
}
export declare namespace Modification {
    class CoerceAtLeast<T extends any> extends Modification<T> {
        readonly value: T;
        constructor(value: T);
        static properties: string[];
        static propertyTypes(T: ReifiedType): {
            value: ReifiedType<unknown>;
        };
        copy: (values: Partial<CoerceAtLeast<T>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: T): T;
        invokeDefault(): T;
    }
}
export declare namespace Modification {
    class Increment<T extends number> extends Modification<T> {
        readonly by: T;
        constructor(by: T);
        static properties: string[];
        static propertyTypes(T: ReifiedType): {
            by: ReifiedType<unknown>;
        };
        copy: (values: Partial<Increment<T>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: T): T;
        invokeDefault(): T;
    }
}
export declare namespace Modification {
    class Multiply<T extends number> extends Modification<T> {
        readonly by: T;
        constructor(by: T);
        static properties: string[];
        static propertyTypes(T: ReifiedType): {
            by: ReifiedType<unknown>;
        };
        copy: (values: Partial<Multiply<T>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: T): T;
        invokeDefault(): T;
    }
}
export declare namespace Modification {
    class AppendString extends Modification<string> {
        readonly value: string;
        constructor(value: string);
        static properties: string[];
        static propertyTypes(): {
            value: StringConstructor[];
        };
        copy: (values: Partial<AppendString>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: string): string;
        invokeDefault(): string;
    }
}
export declare namespace Modification {
    class AppendList<T extends any> extends Modification<Array<T>> {
        readonly items: Array<T>;
        constructor(items: Array<T>);
        static properties: string[];
        static propertyTypes(T: ReifiedType): {
            items: (ArrayConstructor | ReifiedType<unknown>)[];
        };
        copy: (values: Partial<AppendList<T>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: Array<T>): Array<T>;
        invokeDefault(): Array<T>;
    }
}
export declare namespace Modification {
    class AppendSet<T extends any> extends Modification<Array<T>> {
        readonly items: Array<T>;
        constructor(items: Array<T>);
        static properties: string[];
        static propertyTypes(T: ReifiedType): {
            items: (ArrayConstructor | ReifiedType<unknown>)[];
        };
        copy: (values: Partial<AppendSet<T>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: Array<T>): Array<T>;
        invokeDefault(): Array<T>;
    }
}
export declare namespace Modification {
    class Remove<T extends any> extends Modification<Array<T>> {
        readonly condition: Condition<T>;
        constructor(condition: Condition<T>);
        static properties: string[];
        static propertyTypes(T: ReifiedType): {
            condition: (ReifiedType<unknown> | typeof Condition)[];
        };
        copy: (values: Partial<Remove<T>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: Array<T>): Array<T>;
        invokeDefault(): Array<T>;
    }
}
export declare namespace Modification {
    class RemoveInstances<T extends any> extends Modification<Array<T>> {
        readonly items: Array<T>;
        constructor(items: Array<T>);
        static properties: string[];
        static propertyTypes(T: ReifiedType): {
            items: (ArrayConstructor | ReifiedType<unknown>)[];
        };
        copy: (values: Partial<RemoveInstances<T>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: Array<T>): Array<T>;
        invokeDefault(): Array<T>;
    }
}
export declare namespace Modification {
    class DropFirst<T extends any> extends Modification<Array<T>> {
        constructor();
        invoke(on: Array<T>): Array<T>;
        invokeDefault(): Array<T>;
        hashCode(): number;
        equals(other: (any | null)): boolean;
    }
}
export declare namespace Modification {
    class DropLast<T extends any> extends Modification<Array<T>> {
        constructor();
        invoke(on: Array<T>): Array<T>;
        invokeDefault(): Array<T>;
        hashCode(): number;
        equals(other: (any | null)): boolean;
    }
}
export declare namespace Modification {
    class PerElement<T extends any> extends Modification<Array<T>> {
        readonly condition: Condition<T>;
        readonly modification: Modification<T>;
        constructor(condition: Condition<T>, modification: Modification<T>);
        static properties: string[];
        static propertyTypes(T: ReifiedType): {
            condition: (ReifiedType<unknown> | typeof Condition)[];
            modification: (ReifiedType<unknown> | typeof Modification)[];
        };
        copy: (values: Partial<PerElement<T>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: Array<T>): Array<T>;
        invokeDefault(): Array<T>;
    }
}
export declare namespace Modification {
    class Combine<T extends any> extends Modification<Map<string, T>> {
        readonly map: Map<string, T>;
        constructor(map: Map<string, T>);
        static properties: string[];
        static propertyTypes(T: ReifiedType): {
            map: (MapConstructor | ReifiedType<unknown>)[];
        };
        copy: (values: Partial<Combine<T>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: Map<string, T>): Map<string, T>;
        invokeDefault(): Map<string, T>;
    }
}
export declare namespace Modification {
    class ModifyByKey<T extends any> extends Modification<Map<string, T>> {
        readonly map: Map<string, Modification<T>>;
        constructor(map: Map<string, Modification<T>>);
        static properties: string[];
        static propertyTypes(T: ReifiedType): {
            map: (MapConstructor | StringConstructor[] | (ReifiedType<unknown> | typeof Modification)[])[];
        };
        copy: (values: Partial<ModifyByKey<T>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: Map<string, T>): Map<string, T>;
        invokeDefault(): Map<string, T>;
    }
}
export declare namespace Modification {
    class RemoveKeys<T extends any> extends Modification<Map<string, T>> {
        readonly fields: Set<string>;
        constructor(fields: Set<string>);
        static properties: string[];
        static propertyTypes(T: ReifiedType): {
            fields: (SetConstructor | StringConstructor[])[];
        };
        copy: (values: Partial<RemoveKeys<T>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: Map<string, T>): Map<string, T>;
        invokeDefault(): Map<string, T>;
    }
}
export declare namespace Modification {
    class OnField<K extends any, V extends any> extends Modification<K> {
        readonly key: DataClassProperty<K, V>;
        readonly modification: Modification<V>;
        constructor(key: DataClassProperty<K, V>, modification: Modification<V>);
        static properties: string[];
        static propertyTypes(K: ReifiedType, V: ReifiedType): {
            key: (ReifiedType<unknown> | typeof DataClassProperty)[];
            modification: (ReifiedType<unknown> | typeof Modification)[];
        };
        copy: (values: Partial<OnField<K, V>>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
        invoke(on: K): K;
        invokeDefault(): K;
    }
}
