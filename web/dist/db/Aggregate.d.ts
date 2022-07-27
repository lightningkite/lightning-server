export declare class Aggregate {
    private constructor();
    static Sum: Aggregate;
    static Average: Aggregate;
    static StandardDeviationSample: Aggregate;
    static StandardDeviationPopulation: Aggregate;
    private static _values;
    static values(): Array<Aggregate>;
    readonly name: string;
    readonly jsonName: string;
    static valueOf(name: string): Aggregate;
    toString(): string;
    toJSON(): string;
    static fromJSON(key: string): Aggregate;
}
export declare function xAggregateAggregator(this_: Aggregate): Aggregator;
export interface Aggregator {
    consume(value: number): void;
    complete(): (number | null);
}
export declare class SumAggregator implements Aggregator {
    static implementsAggregator: boolean;
    constructor();
    current: number;
    anyFound: boolean;
    consume(value: number): void;
    complete(): (number | null);
}
export declare class AverageAggregator implements Aggregator {
    static implementsAggregator: boolean;
    constructor();
    count: number;
    current: number;
    consume(value: number): void;
    complete(): (number | null);
}
export declare class StandardDeviationSampleAggregator implements Aggregator {
    static implementsAggregator: boolean;
    constructor();
    count: number;
    mean: number;
    m2: number;
    consume(value: number): void;
    complete(): (number | null);
}
export declare class StandardDeviationPopulationAggregator implements Aggregator {
    static implementsAggregator: boolean;
    constructor();
    count: number;
    mean: number;
    m2: number;
    consume(value: number): void;
    complete(): (number | null);
}
export declare function xSequenceAggregate(this_: Iterable<number>, aggregate: Aggregate): (number | null);
