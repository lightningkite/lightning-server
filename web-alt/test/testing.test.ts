import {Condition, evaluateCondition, evaluateModification} from "../src";

interface TestType {
    numbers: Array<number>
    name: string
}

test('Valid condition types work', () => {
    const condition: Condition<TestType> = {
        numbers: { AnyElements: { GreaterThan: 2 }}
    }
})
let item: TestType = {
    numbers: [1, 2, 3, 4],
    name: "Bob"
}
test('Equal true', () => { expect(evaluateCondition({ name: { Equal: "Bob" } }, item)).toBe(true) })
test('Equal false', () => { expect(evaluateCondition({ name: { Equal: "Bobby" } }, item)).toBe(false) })
test('GreaterThan true', () => { expect(evaluateCondition({ name: { GreaterThan: "A" } }, item)).toBe(true) })
test('GreaterThan equal', () => { expect(evaluateCondition({ name: { GreaterThan: "Bob" } }, item)).toBe(false) })
test('GreaterThan false', () => { expect(evaluateCondition({ name: { GreaterThan: "C" } }, item)).toBe(false) })
test('LessThan true', () => { expect(evaluateCondition({ name: { LessThan: "A" } }, item)).toBe(false) })
test('LessThan equal', () => { expect(evaluateCondition({ name: { LessThan: "Bob" } }, item)).toBe(false) })
test('LessThan false', () => { expect(evaluateCondition({ name: { LessThan: "C" } }, item)).toBe(true) })
test('GreaterThanOrEqual true', () => { expect(evaluateCondition({ name: { GreaterThanOrEqual: "A" } }, item)).toBe(true) })
test('GreaterThanOrEqual equal', () => { expect(evaluateCondition({ name: { GreaterThanOrEqual: "Bob" } }, item)).toBe(true) })
test('GreaterThanOrEqual false', () => { expect(evaluateCondition({ name: { GreaterThanOrEqual: "C" } }, item)).toBe(false) })
test('LessThanOrEqual true', () => { expect(evaluateCondition({ name: { LessThanOrEqual: "A" } }, item)).toBe(false) })
test('LessThanOrEqual equal', () => { expect(evaluateCondition({ name: { LessThanOrEqual: "Bob" } }, item)).toBe(true) })
test('LessThanOrEqual false', () => { expect(evaluateCondition({ name: { LessThanOrEqual: "C" } }, item)).toBe(true) })
test('And Empty', () => { expect(evaluateCondition({ name: { And: [] } }, item)).toBe(true) })
test('Or Empty', () => { expect(evaluateCondition({ name: { Or: [] } }, item)).toBe(false) })
test('And Single', () => { expect(evaluateCondition({ name: { And: [ { Always: true } ] } }, item)).toBe(true) })
test('Or Single', () => { expect(evaluateCondition({ name: { Or: [ { Always: true } ] } }, item)).toBe(true) })
test('And Single 2', () => { expect(evaluateCondition({ name: { And: [ { Never: true } ] } }, item)).toBe(false) })
test('Or Single 2', () => { expect(evaluateCondition({ name: { Or: [ { Never: true } ] } }, item)).toBe(false) })
test('And Mixed', () => { expect(evaluateCondition({ name: { And: [ { Always: true }, { Never: true } ] } }, item)).toBe(false) })
test('Or Mixed', () => { expect(evaluateCondition({ name: { Or: [ { Always: true }, { Never: true } ] } }, item)).toBe(true) })
test('Not', () => { expect(evaluateCondition({ name: { Not: { Never: true } } }, item)).toBe(true) })
test('Inside', () => { expect(evaluateCondition({ name: { Inside: ["Bob", "Bobby"] } }, item)).toBe(true) })
test('Inside false', () => { expect(evaluateCondition({ name: { Inside: ["Dude", "Guy"] } }, item)).toBe(false) })
test('Not Inside', () => { expect(evaluateCondition({ name: { NotInside: ["Bob", "Bobby"] } }, item)).toBe(false) })
test('Not Inside false', () => { expect(evaluateCondition({ name: { NotInside: ["Dude", "Guy"] } }, item)).toBe(true) })
test('Search', () => { expect(evaluateCondition({ name: { Search: { value: "ob", ignoreCase: true } } }, item)).toBe(true) })
test('Search false', () => { expect(evaluateCondition({ name: { Search: { value: "as", ignoreCase: true } } }, item)).toBe(false) })
test('Search case sensitive', () => { expect(evaluateCondition({ name: { Search: { value: "bo", ignoreCase: false } } }, item)).toBe(false) })
test('Any', () => { expect(evaluateCondition({ numbers: { AnyElements: { GreaterThan: 3 } } }, item)).toBe(true) })
test('All', () => { expect(evaluateCondition({ numbers: { AllElements: { GreaterThan: 3 } } }, item)).toBe(false) })

test('Assign', () => { expect(evaluateModification({ name: { Assign: "asdf"} }, item).name).toBe("asdf") })