import {
  simplify,
  finalSimplify,
  reduceAnd,
  reduceOr,
  SerializableProperty,
  Condition,
} from "../src";

// Helper for mock field
const field = (name: string): SerializableProperty<any, any> => ({ name });

describe("Condition Simplify", () => {

  test("simplify And flattens nested Ands", () => {
    const cond: Condition<number> = {
      And: [
        { And: [{ Always: true }, { Never: true }] },
        { Always: true },
      ],
    };
    const result = simplify(cond);
    expect(result).toEqual({ Never: true });
  });

  test("simplify Or flattens nested Ors", () => {
    const cond: Condition<number> = {
      Or: [
        { Or: [{ Always: true }, { Never: true }] },
        { Never: true },
      ],
    };
    const result = simplify(cond);
    expect(result).toEqual({ Always: true });
  });

  test("finalSimplify removes empty Inside", () => {
    const cond: Condition<number> = { Inside: [] };
    const result = finalSimplify(cond);
    expect(result).toEqual({ Never: true });
  });

  test("finalSimplify removes empty NotInside", () => {
    const cond: Condition<number> = { NotInside: [] };
    const result = finalSimplify(cond);
    expect(result).toEqual({ Always: true });
  });

  test("AND combining Always/Never behaves correctly", () => {
    expect(reduceAnd({ Always: true }, { Never: true })).toEqual({ Never: true });
    expect(reduceAnd({ Always: true }, { Always: true })).toEqual({ Always: true });
    expect(reduceAnd({ Never: true }, { Always: true })).toEqual({ Never: true });
  });

  test("OR combining Always/Never behaves correctly", () => {
    expect(reduceOr({ Always: true }, { Never: true })).toEqual({ Always: true });
    expect(reduceOr({ Never: true }, { Never: true })).toEqual({ Never: true });
    expect(reduceOr({ Always: true }, { Always: true })).toEqual({ Always: true });
  });

  test("AND merges nested AND structures", () => {
    const result = reduceAnd({ And: [{ Always: true }] }, { And: [{ Never: true }] });
    expect(result).toEqual({ And: [{ Always: true }, { Never: true }] });
  });

  test("OR merges nested OR structures", () => {
    const result = reduceOr({ Or: [{ Always: true }] }, { Or: [{ Never: true }] });
    expect(result).toEqual({ Or: [{ Always: true }, { Never: true }] });
  });

  test("OnField nested simplification reduces properly", () => {
    const cond: Condition<any> = {
      And: [
        {
          OnField: {
            key: field("age"),
            condition: { Always: true },
          } as any,
        },
        {
          OnField: {
            key: field("age"),
            condition: { Never: true },
          },
        },
      ],
    };
    const result = simplify(cond);
    expect(result).toEqual({ Never: true });
  });

  test("Empty And becomes Always", () => {
    const cond: Condition<number> = { And: [] };
    const result = simplify(cond);
    expect(result).toEqual({ Always: true });
  });

  test("Empty Or becomes Never", () => {
    const cond: Condition<number> = { Or: [] };
    const result = simplify(cond);
    expect(result).toEqual({ Never: true });
  });

  test("Nested OnField structure flattens through simplify", () => {
    const cond: Condition<any> = {
      OnField: {
        key: field("user"),
        condition: {
          OnField: {
            key: field("age"),
            condition: { Always: true },
          },
        },
      } as any,
    };
    const result = simplify(cond);
    // Should remain logically the same
    expect(result).toEqual(cond);
  });

});
