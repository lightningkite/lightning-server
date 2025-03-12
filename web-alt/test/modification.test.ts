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

const numberList = [1, 1, 2, 3, 5];

describe("List operations", () => {
  test("ListRemove", () => {
    expect(
      evaluateModification({ ListRemove: { Equal: 1 } }, numberList)
    ).toMatchObject([2, 3, 5]);
  });
});
