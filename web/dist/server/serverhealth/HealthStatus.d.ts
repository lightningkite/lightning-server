import { Instant } from '@js-joda/core';
export declare class HealthStatus {
    readonly level: HealthStatus.Level;
    readonly checkedAt: Instant;
    readonly additionalMessage: (string | null);
    constructor(level: HealthStatus.Level, checkedAt?: Instant, additionalMessage?: (string | null));
    static properties: string[];
    static propertyTypes(): {
        level: (typeof HealthStatus.Level)[];
        checkedAt: (typeof Instant)[];
        additionalMessage: StringConstructor[];
    };
    copy: (values: Partial<HealthStatus>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
}
export declare namespace HealthStatus {
    class Level {
        readonly color: string;
        private constructor();
        static OK: Level;
        static WARNING: Level;
        static URGENT: Level;
        static ERROR: Level;
        private static _values;
        static values(): Array<Level>;
        readonly name: string;
        readonly jsonName: string;
        static valueOf(name: string): Level;
        toString(): string;
        toJSON(): string;
        static fromJSON(key: string): Level;
    }
}
export declare class ServerHealth {
    readonly serverId: string;
    readonly version: string;
    readonly memory: ServerHealth.Memory;
    readonly features: Map<string, HealthStatus>;
    readonly loadAverageCpu: number;
    constructor(serverId: string, version: string, memory: ServerHealth.Memory, features: Map<string, HealthStatus>, loadAverageCpu: number);
    static properties: string[];
    static propertyTypes(): {
        serverId: StringConstructor[];
        version: StringConstructor[];
        memory: (typeof ServerHealth.Memory)[];
        features: (MapConstructor | StringConstructor[] | (typeof HealthStatus)[])[];
        loadAverageCpu: NumberConstructor[];
    };
    copy: (values: Partial<ServerHealth>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
}
export declare namespace ServerHealth {
    class Memory {
        readonly maxMem: number;
        readonly totalMemory: number;
        readonly freeMemory: number;
        readonly systemAllocated: number;
        readonly memUsagePercent: number;
        constructor(maxMem: number, totalMemory: number, freeMemory: number, systemAllocated: number, memUsagePercent: number);
        static properties: string[];
        static propertyTypes(): {
            maxMem: NumberConstructor[];
            totalMemory: NumberConstructor[];
            freeMemory: NumberConstructor[];
            systemAllocated: NumberConstructor[];
            memUsagePercent: NumberConstructor[];
        };
        copy: (values: Partial<Memory>) => this;
        equals: (other: any) => boolean;
        hashCode: () => number;
    }
}
