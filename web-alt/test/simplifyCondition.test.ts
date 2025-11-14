import {
  simplify,
  finalSimplify,
  reduceAnd,
  reduceOr,
  Condition,
} from "../src";
import { inspect } from "util";
// Helper for mock field
// const field = (name: string): SerializableProperty<any, any> => ({ name });

const log = (...args: any[]) => {
  console.log(args.map((x) => inspect(x, { depth: null })));
};

type TestCond<T> = {
  original: Condition<T>;
  simple: Condition<T>;
};

describe("Condition Simplify", () => {
  // test("simplify And flattens nested Ands", () => {
  //   const cond: Condition<number> = {
  //     And: [{ And: [{ Always: true }, { Never: true }] }, { Always: true }],
  //   };
  //   const result = simplify(cond);
  //   expect(result).toEqual({ Never: true });
  // });

  // test("simplify Or flattens nested Ors", () => {
  //   const cond: Condition<number> = {
  //     Or: [{ Or: [{ Always: true }, { Never: true }] }, { Never: true }],
  //   };
  //   const result = simplify(cond);
  //   expect(result).toEqual({ Always: true });
  // });

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

  // test("OnField nested simplification reduces properly", () => {
  //   const cond: Condition<{ age: number }> = {
  //     And: [{ age: { Always: true } }, { age: { Never: true } }],
  //   };
  //   const result = simplify(cond);

  //   expect(result).toEqual({ Never: true });
  // });
  // test("OnField nested simplification And", () => {
  //   const cond: Condition<{ age: number }> = {
  //     And: [
  //       { age: { Equal: 4 } },
  //       { Always: true },
  //       { age: { GreaterThan: 2 } },
  //     ],
  //   };

  //   expect(simplify(cond)).toMatchObject({
  //     age: { And: [{ Equal: 4 }, { GreaterThan: 2 }] },
  //   });
  // });

  // test("OnField nested simplification not needed", () => {
  //   const condition: TestCond<{ age: number }> = {
  //     original: {
  //       And: [
  //         { age: { Equal: 4 } },
  //         { age: { Or: [{ GreaterThan: 2 }, { Equal: 8 }] } },
  //       ],
  //     },
  //     simple: {
  //       age: {
  //         And: [{ Equal: 4 }, { Or: [{ GreaterThan: 2 }, { Equal: 8 }] }],
  //       },
  //     },
  //   };

  //   expect(simplify(condition.original)).toMatchObject(condition.simple);
  // });
  // test("Nested fields", () => {
  //   const condition: TestCond<{ age: number }> = {
  //     original: {
  //       And: [
  //         {
  //           And: [
  //             {
  //               And: [
  //                 {
  //                   And: [
  //                     {
  //                       And: [
  //                         {
  //                           And: [
  //                             {
  //                               And: [
  //                                 { And: [{ And: [{ age: { Equal: 4 } }] }] },
  //                                 { age: { NotEqual: 4 } },
  //                               ],
  //                             },
  //                           ],
  //                         },
  //                       ],
  //                     },
  //                   ],
  //                 },
  //               ],
  //             },
  //           ],
  //         },
  //       ],
  //     },
  //     simple: {
  //       age: {
  //         And: [{ Equal: 4 }, { NotEqual: 4 }],
  //       },
  //     },
  //   };

  //   expect(simplify(condition.original)).toMatchObject(condition.simple);
  // });
  // test("Never true for field", () => {
  //   const condition: TestCond<{ age: number }> = {
  //     original: {
  //       And: [
  //         {
  //           And: [
  //             {
  //               And: [
  //                 {
  //                   And: [
  //                     {
  //                       And: [
  //                         {
  //                           And: [
  //                             { And: [{ And: [{ age: { Equal: 4 } }] }] },
  //                             { age: { Never: true } },
  //                           ],
  //                         },
  //                       ],
  //                     },
  //                   ],
  //                 },
  //               ],
  //             },
  //           ],
  //         },
  //       ],
  //     },
  //     simple: { Never: true },
  //   };

  //   expect(simplify(condition.original)).toMatchObject(condition.simple);
  // });
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
          { age: { Equal: 3 } },
          { age: { Or: [{ Equal: 1 }, { Equal: 2 }] } },
          { name: { Equal: "123" } },
        ],
      },
    };

    log(simplify(condition.original))
    expect(simplify(condition.original)).toMatchObject(condition.simple);
  });

  // test("Empty And becomes Always", () => {
  //   const cond: Condition<number> = { And: [] };
  //   const result = simplify(cond);
  //   expect(result).toEqual({ Always: true });
  // });

  // test("Empty Or becomes Never", () => {
  //   const cond: Condition<number> = { Or: [] };
  //   const result = simplify(cond);
  //   expect(result).toEqual({ Never: true });
  // });

  // test("Nested OnField structure flattens through simplify", () => {
  //   const cond: Condition<{ user: { age: number } }> = {
  //     user: {
  //       age: { Always: true },
  //     },
  //   };
  //   const result = simplify(cond);
  //   expect(result).toEqual(cond);
  // });
});
