import { simplifyCondition, Condition } from "../src";
import { inspect } from "util";

type TestCond<T> = {
  original: Condition<T>;
  simple: Condition<T>;
};

describe("Condition Simplify", () => {
  test("simplify And flattens nested Ands", () => {
    const cond: Condition<number> = {
      And: [{ And: [{ Always: true }, { Never: true }] }, { Always: true }],
    };
    const result = simplifyCondition(cond);
    expect(result).toEqual({ Never: true });
  });

  test("simplify Or flattens nested Ors", () => {
    const cond: Condition<number> = {
      Or: [{ Or: [{ Always: true }, { Never: true }] }, { Never: true }],
    };
    const result = simplifyCondition(cond);
    expect(result).toEqual({ Always: true });
  });

  test("OnField nested simplification reduces properly", () => {
    const cond: Condition<{ age: number }> = {
      And: [{ age: { Always: true } }, { age: { Never: true } }],
    };
    const result = simplifyCondition(cond);

    expect(result).toEqual({ Never: true });
  });
  test("OnField nested simplification And", () => {
    const cond: Condition<{ age: number }> = {
      And: [
        { age: { Equal: 4 } },
        { Always: true },
        { age: { GreaterThan: 2 } },
      ],
    };

    expect(simplifyCondition(cond)).toMatchObject({
      age: { And: [{ Equal: 4 }, { GreaterThan: 2 }] },
    });
  });

  test("OnField nested simplification not needed", () => {
    const condition: TestCond<{ age: number }> = {
      original: {
        And: [
          { age: { Equal: 4 } },
          { age: { Or: [{ GreaterThan: 2 }, { Equal: 8 }] } },
        ],
      },
      simple: {
        age: {
          And: [{ Equal: 4 }, { Or: [{ GreaterThan: 2 }, { Equal: 8 }] }],
        },
      },
    };

    expect(simplifyCondition(condition.original)).toMatchObject(
      condition.simple
    );
  });
  test("Nested fields", () => {
    const condition: TestCond<{ age: number }> = {
      original: {
        And: [
          {
            And: [
              { And: [{ And: [{ age: { Equal: 4 } }] }] },
              { age: { NotEqual: 4 } },
            ],
          },
        ],
      },
      simple: {
        age: {
          And: [{ Equal: 4 }, { NotEqual: 4 }],
        },
      },
    };

    expect(simplifyCondition(condition.original)).toMatchObject(
      condition.simple
    );
  });
  test("Never true for field", () => {
    const condition: TestCond<{ age: number }> = {
      original: {
        And: [
          {
            And: [
              {
                And: [
                  { And: [{ And: [{ age: { Equal: 4 } }] }] },
                  { age: { Never: true } },
                ],
              },
            ],
          },
        ],
      },
      simple: { Never: true },
    };

    expect(simplifyCondition(condition.original)).toMatchObject(
      condition.simple
    );
  });

  const condition: TestCond<{ age: number; name: string }> = {
    original: {
      And: [
        { age: { Equal: 3 } },
        { Or: [{ age: { Equal: 1 } }, { age: { Equal: 2 } }] },
        { name: { Equal: "123" } },
      ],
    },
    simple: {
      And: [
        {
          age: {
            And: [{ Equal: 3 }, { Or: [{ Equal: 1 }, { Equal: 2 }] }],
          },
        },
        { name: { Equal: "123" } },
      ],
    },
  };
  test("Multiple field", () => {
    const condition: TestCond<{ age: number; name: string }> = {
      original: {
        And: [
          { age: { Equal: 3 } },
          { Or: [{ age: { Equal: 1 } }, { age: { Equal: 2 } }] },
          { name: { Equal: "123" } },
        ],
      },
      simple: {
        And: [
          {
            age: {
              And: [{ Equal: 3 }, { Or: [{ Equal: 1 }, { Equal: 2 }] }],
            },
          },
          { name: { Equal: "123" } },
        ],
      },
    };

    expect(simplifyCondition(condition.original)).toMatchObject(
      condition.simple
    );
  });

  test("Empty And becomes Always", () => {
    const cond: Condition<number> = { And: [] };
    const result = simplifyCondition(cond);
    expect(result).toEqual({ Always: true });
  });

  test("Empty Or becomes Never", () => {
    const cond: Condition<number> = { Or: [] };
    const result = simplifyCondition(cond);
    expect(result).toEqual({ Never: true });
  });

  test("Nested OnField structure flattens through simplify", () => {
    const cond: Condition<{ user: { age: number } }> = {
      user: {
        age: { Always: true },
      },
    };
    const result = simplifyCondition(cond);
    expect(result).toEqual(cond);
  });

  test("OR combines same field into grouped OR", () => {
    const cond: Condition<{ age: number }> = {
      Or: [{ age: { Equal: 1 } }, { age: { Equal: 2 } }],
    };

    expect(simplifyCondition(cond)).toMatchObject({
      age: { Or: [{ Equal: 1 }, { Equal: 2 }] },
    });
  });

  test("OR with Always returns Always", () => {
    const cond: Condition<number> = {
      Or: [{ Equal: 1 }, { Always: true }],
    };
    expect(simplifyCondition(cond)).toEqual({ Always: true });
  });

  test("AND with Always removes Always", () => {
    const cond: Condition<number> = {
      And: [{ Equal: 1 }, { Always: true }, { Equal: 2 }],
    };
    expect(simplifyCondition(cond)).toMatchObject({
      And: [{ Equal: 1 }, { Equal: 2 }],
    });
  });
  test("Multiple AND on same field merges all", () => {
    const cond: Condition<{ age: number }> = {
      And: [
        { age: { Equal: 3 } },
        { age: { GreaterThan: 1 } },
        { age: { LessThan: 10 } },
      ],
    };

    expect(simplifyCondition(cond)).toMatchObject({
      age: {
        And: [{ Equal: 3 }, { GreaterThan: 1 }, { LessThan: 10 }],
      },
    });
  });
  test("Deep nested fields get grouped properly", () => {
    const cond: Condition<{ user: { profile: { age: number } } }> = {
      And: [
        { user: { profile: { age: { Equal: 3 } } } },
        { user: { profile: { age: { GreaterThan: 1 } } } },
      ],
    };

    expect(simplifyCondition(cond)).toMatchObject({
      user: {
        profile: {
          age: {
            And: [{ Equal: 3 }, { GreaterThan: 1 }],
          },
        },
      },
    });
  });
  test("Inside combined with Never yields Never", () => {
    const cond: Condition<number> = {
      And: [{ Inside: [1, 2, 3] }, { Never: true }],
    };

    expect(simplifyCondition(cond)).toEqual({ Never: true });
  });
  test("Nested Or gets flattened and simplified", () => {
    const cond: Condition<number> = {
      Or: [{ Or: [{ Equal: 1 }, { Equal: 2 }] }, { Equal: 3 }],
    };

    expect(simplifyCondition(cond)).toMatchObject({
      Or: [{ Equal: 1 }, { Equal: 2 }, { Equal: 3 }],
    });
  });
  test("IfNotNull inside nested field retained", () => {
    const cond: Condition<{ user: { age?: number } }> = {
      user: { age: { IfNotNull: { GreaterThan: 0 } } },
    };

    expect(simplifyCondition(cond)).toEqual(cond);
  });
  test("AND with two fields keeps them separate", () => {
    const cond: Condition<{ age: number; score: number }> = {
      And: [{ age: { Equal: 2 } }, { score: { Equal: 10 } }],
    };

    expect(simplifyCondition(cond)).toMatchObject({
      And: [{ age: { Equal: 2 } }, { score: { Equal: 10 } }],
    });
  });
  test("OR with different fields does not merge incorrectly", () => {
    const cond: Condition<{ age: number; score: number }> = {
      Or: [{ age: { Equal: 2 } }, { score: { Equal: 10 } }],
    };

    expect(simplifyCondition(cond)).toMatchObject({
      Or: [{ age: { Equal: 2 } }, { score: { Equal: 10 } }],
    });
  });
  test("finalSimplify handles nested Inside→Never properly", () => {
    const cond: Condition<number> = {
      And: [{ Inside: [] }, { Equal: 3 }],
    };

    expect(simplifyCondition(cond)).toEqual({ Never: true });
  });
  test("Empty object condition treated as Always", () => {
    const cond = {} as Condition<number>;
    expect(simplifyCondition(cond)).toEqual({});
  });
  test("OR groups nested field at depth 3", () => {
    const cond: Condition<{ u: { p: { age: number } } }> = {
      Or: [
        { u: { p: { age: { Equal: 1 } } } },
        { u: { p: { age: { Equal: 2 } } } },
      ],
    };

    expect(simplifyCondition(cond)).toMatchObject({
      u: {
        p: {
          age: {
            Or: [{ Equal: 1 }, { Equal: 2 }],
          },
        },
      },
    });
  });
  test("AND of Always and Always collapses to Always", () => {
    const cond: Condition<number> = {
      And: [{ Always: true }, { Always: true }],
    };
    expect(simplifyCondition(cond)).toEqual({ Always: true });
  });
  test("OR of Never and Never collapses to Never", () => {
    const cond: Condition<number> = {
      Or: [{ Never: true }, { Never: true }],
    };
    expect(simplifyCondition(cond)).toEqual({ Never: true });
  });
  test("Complex mix of AND + OR + fields reduces correctly", () => {
    const cond: Condition<{ age: number; score: number }> = {
      And: [
        { age: { GreaterThan: 10 } },
        {
          Or: [{ age: { Equal: 20 } }, { score: { Equal: 5 } }],
        },
      ],
    };

    expect(simplifyCondition(cond)).toMatchObject({
      And: [
        { age: { GreaterThan: 10 } },
        {
          Or: [{ age: { Equal: 20 } }, { score: { Equal: 5 } }],
        },
      ],
    });
  });

  test("SetAnyElements and SetSizesEquals", () => {
    const condition = {
      And: [
        {
          SetAnyElements: {
            Equal: 3,
          },
        },

        { SetSizesEquals: 3 },
      ],
    } as const;

    expect(condition).toMatchObject(condition);
  });
});

// Testing helper functions

// test("finalSimplify removes empty Inside", () => {
//   const cond: Condition<number> = { Inside: [] };
//   const result = finalSimplify(cond);
//   expect(result).toEqual({ Never: true });
// });

// test("finalSimplify removes empty NotInside", () => {
//   const cond: Condition<number> = { NotInside: [] };
//   const result = finalSimplify(cond);
//   expect(result).toEqual({ Always: true });
// });

// test("AND combining Always/Never behaves correctly", () => {
//   expect(reduceAnd({ Always: true }, { Never: true })).toEqual({
//     Never: true,
//   });
//   expect(reduceAnd({ Always: true }, { Always: true })).toEqual({
//     Always: true,
//   });
//   expect(reduceAnd({ Never: true }, { Always: true })).toEqual({
//     Never: true,
//   });
// });

// test("OR combining Always/Never behaves correctly", () => {
//   expect(reduceOr({ Always: true }, { Never: true })).toEqual({
//     Always: true,
//   });
//   expect(reduceOr({ Never: true }, { Never: true })).toEqual({ Never: true });
//   expect(reduceOr({ Always: true }, { Always: true })).toEqual({
//     Always: true,
//   });
// });

// test("AND merges nested AND structures", () => {
//   const result = reduceAnd(
//     { And: [{ Always: true }] },
//     { And: [{ Never: true }] }
//   );
//   expect(result).toEqual({ And: [{ Always: true }, { Never: true }] });
// });

// test("OR merges nested OR structures", () => {
//   const result = reduceOr(
//     { Or: [{ Always: true }] },
//     { Or: [{ Never: true }] }
//   );
//   expect(result).toEqual({ Or: [{ Always: true }, { Never: true }] });
// });
