import { evaluateModification } from "../src";

const testPerson = {
  name: "Bob",
  age: 24,
  gender: "male",
  parents: ["John", "Jane"],
};
const copyPerson = (partial: Partial<typeof testPerson>) => ({
  ...testPerson,
  ...partial,
});

describe("evaluateModification", () => {
  test("Assign", () => {
    expect(evaluateModification({ Assign: "bar" }, "foo")).toBe("bar");
  });
  test("Assign", () => {
    expect(evaluateModification({ Assign: 34 }, 2)).toBe(34);
  });
  test("Assign Object", () => {
    expect(
      evaluateModification(
        {
          Assign: {
            name: "B",
            age: 21,
            gender: "female",
            parents: ["John1", "Jane1"],
          },
        },
        testPerson
      )
    ).toMatchObject({
      name: "B",
      age: 21,
      gender: "female",
      parents: ["John1", "Jane1"],
    });
  });
  test("Assign Field", () => {
    expect(
      evaluateModification({ name: { Assign: "Ron" } }, testPerson)
    ).toMatchObject(copyPerson({ name: "Ron" }));
  });
});

describe("Chain", () => {
  test("Chain", () => {
    expect(
      evaluateModification(
        {
          Chain: [{ name: { Assign: "Ron" } }, { age: { Assign: 21 } }],
        },
        testPerson
      )
    ).toMatchObject(copyPerson({ name: "Ron", age: 21 }));
  });
});

const numberList = [1, 1, 2, 3, 5];

describe("List operations", () => {
  test("ListRemove", () => {
    expect(
      evaluateModification({ ListRemove: { Equal: 1 } }, numberList)
    ).toMatchObject([2, 3, 5]);
    expect(
      evaluateModification({ SetRemove: { Equal: 1 } }, numberList)
    ).toMatchObject([2, 3, 5]);
  });
  test("ListAppend", () => {
    expect(
      evaluateModification({ ListAppend: [8, 13] }, numberList)
    ).toMatchObject([1, 1, 2, 3, 5, 8, 13]);
    expect(
      evaluateModification({ SetAppend: [8, 13] }, numberList)
    ).toMatchObject([1, 1, 2, 3, 5, 8, 13]);
  });
  test("ListDropFirst", () => {
    expect(
      evaluateModification({ ListDropFirst: true }, numberList)
    ).toMatchObject([1, 2, 3, 5]);
    expect(
      evaluateModification({ SetDropFirst: true }, numberList)
    ).toMatchObject([1, 2, 3, 5]);
  });

  test("ListDropLast", () => {
    expect(
      evaluateModification({ ListDropLast: true }, numberList)
    ).toMatchObject([1, 1, 2, 3]);
    expect(
      evaluateModification({ SetDropLast: true }, numberList)
    ).toMatchObject([1, 1, 2, 3]);
  });

  test("ListRemoveInstances", () => {
    expect(
      evaluateModification({ ListRemoveInstances: [1, 2] }, numberList)
    ).toMatchObject([3, 5]);
    expect(
      evaluateModification({ SetRemoveInstances: [1, 2] }, numberList)
    ).toMatchObject([3, 5]);
  });

  test("ListPerElement", () => {
    expect(
      evaluateModification(
        {
          ListPerElement: {
            condition: { Equal: 1 },
            modification: { Assign: 0 },
          },
        },
        numberList
      )
    ).toMatchObject([0, 0, 2, 3, 5]);
    expect(
      evaluateModification(
        {
          SetPerElement: {
            condition: { GreaterThan: 1 },
            modification: { Assign: 0 },
          },
        },
        numberList
      )
    ).toMatchObject([1, 1, 0, 0, 0]);
  });
});

describe("ComparableModification", () => {
  expect(evaluateModification({ CoerceAtMost: 3 }, 12)).toEqual(3);
  expect(evaluateModification({ CoerceAtLeast: 24 }, 12)).toEqual(24);
  expect(evaluateModification({ CoerceAtMost: "a" }, "b")).toEqual("a");
  expect(evaluateModification({ CoerceAtLeast: "b" }, "a")).toEqual("b");
});

describe("StringModification", () => {
  expect(evaluateModification({ AppendString: " world" }, "hello")).toEqual(
    "hello world"
  );
});

describe("NumberModification", () => {
  expect(evaluateModification({ Increment: 3 }, 2)).toEqual(5);
  expect(evaluateModification({ Multiply: 3 }, 2)).toEqual(6);
});