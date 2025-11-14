import { Condition } from "./Condition";
import { inspect } from "util";
// Helper for mock field
// const field = (name: string): SerializableProperty<any, any> => ({ name });

const log = (...args: any[]) => {
  console.log(args.map((x) => inspect(x, { depth: null })));
};

function isAnd<T>(c: Condition<T>): c is { And: Condition<T>[] } {
  return "And" in c;
}
function isOr<T>(c: Condition<T>): c is { Or: Condition<T>[] } {
  return "Or" in c;
}

function stringIsField(c: string): boolean {
  return ![
    "Never",
    "Always",
    "And",
    "Or",
    "Not",
    "Equal",
    "NotEqual",
    "Inside",
    "NotInside",
    "GreaterThan",
    "LessThan",
    "GreaterThanOrEqual",
    "LessThanOrEqual",
    "IntBitsClear",
    "IntBitsSet",
    "IntBitsAnyClear",
    "IntBitsAnySet",
    "Exists",
    "IfNotNull",
  ].includes(c);
}

function getFieldKey<T extends Condition<any>>(c: T): keyof T | null {
  const fieldKey = Object.keys(c).find((k) => stringIsField(k));
  if (fieldKey) {
    return fieldKey as keyof T;
  }
  return null;
}

function isNever<T>(c: Condition<T>): c is { Never: true } {
  return "Never" in c;
}
function isAlways<T>(c: Condition<T>): c is { Always: true } {
  return "Always" in c;
}
function isInside<T>(c: Condition<T>): c is { Inside: T[] } {
  return "Inside" in c;
}
function isNotInside<T>(c: Condition<T>): c is { NotInside: T[] } {
  return "NotInside" in c;
}

export function simplify<T>(condition: Condition<T>): Condition<T> {
  if (isAnd(condition)) {
    const groups = new Map<string, Array<Condition<any>>>();

    for (const sub of condition.And) {
      for (const [path, subCond] of andByField(sub)) {
        const key = path.join(".");
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key)!.push(subCond);
      }
    }

    const simplified = Array.from(groups.entries())
      .map(([p1, list]) => {
        const reduced = list.reduce((a, b) => reduceAnd(a, b));
        const final = finalSimplify(reduced);
        if (isAlways(final)) return null;
        if (isNever(final)) return { Never: true } as Condition<T>;
        if (stringIsField(p1)) {
          return { [p1]: final };
        }
        return final as Condition<T>;
      })
      .filter(Boolean) as Condition<T>[];

    if (simplified.length === 0) return { Always: true };
    if (simplified.length === 1) return simplified[0];
    return { And: simplified };
  } else if (isOr(condition)) {
    const groups = new Map<string, Array<Condition<any>>>();

    for (const sub of condition.Or) {
      for (const [path, subCond] of orByField(sub)) {
        const key = path.join(".");
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key)!.push(subCond);
      }
    }

    const simplified = Array.from(groups.entries())
      .map(([p1, list]) => {
        const reduced = list.reduce((a, b) => reduceOr(a, b));
        const final = finalSimplify(reduced);
        if (isNever(final)) return null;
        if (isAlways(final)) return { Always: true } as Condition<T>;
        if (stringIsField(p1)) {
          return { [p1]: final };
        }
        return final as Condition<T>;
      })
      .filter(Boolean) as Condition<T>[];

    if (simplified.length === 0) return { Never: true };
    if (simplified.length === 1) return simplified[0];
    return { Or: simplified };
  }

  const field = getFieldKey(condition);
  if (field) {
    const simp = finalSimplify((condition as any)[field]);
    // console.log("COND", (condition as any)[field]);
    // console.log({ simp });
    if (isAlways(simp) || isNever(simp)) {
      return simp as Condition<T>;
    }
    return { [field]: simp } as Condition<T>;
  } else {
    return finalSimplify(condition);
  }
}

export function finalSimplify<T>(cond: Condition<T>): Condition<T> {
  if (isAnd(cond)) {
    if (cond.And.some(isNever)) return { Never: true };
  } else if (isOr(cond)) {
    if (cond.Or.some(isAlways)) return { Always: true };
  } else if (isInside(cond)) {
    if (cond.Inside.length === 0) return { Never: true };
  } else if (isNotInside(cond)) {
    if (cond.NotInside.length === 0) return { Always: true };
  }
  return cond;
}

function andByField(cond: Condition<any>): Array<[string[], Condition<any>]> {
  if (isAnd(cond)) {
    return cond.And.flatMap((it) => andByField(it));
  }
  const onField = getFieldKey(cond);
  if (onField) {
    return andByField(cond[onField]).map(([list, c]) => [
      [onField, ...list],
      c,
    ]);
  } else {
    const s = simplify(cond);
    const onField = getFieldKey(s);
    if (onField) {
      return andByField(s[onField]).map(([list, c]) => [[onField, ...list], c]);
    }
    return [[[], s]];
  }
}

function orByField(cond: Condition<any>): Array<[string[], Condition<any>]> {
  if (isOr(cond)) {
    return cond.Or.flatMap((it) => orByField(it));
  }
  const onField = getFieldKey(cond);
  if (onField) {
    console.log();
    return orByField(cond[onField]).map(([list, c]) => [
      [cond[onField], ...list],
      c,
    ]);
  } else {
    const s = simplify(cond);
    const onField = getFieldKey(s);
    if (onField) {
      return orByField(s[onField]).map(([list, c]) => [[onField, ...list], c]);
    }
    return [[[], s]];
  }
}

export function reduceAnd<T>(a: Condition<T>, b: Condition<T>): Condition<T> {
  if (isAlways(a)) return b;
  if (isNever(a)) return a;
  if (isAlways(b)) return a;
  if (isNever(b)) return b;
  if (isAnd(a) && isAnd(b)) return { And: [...a.And, ...b.And] };
  if (isAnd(a)) return { And: [...a.And, b] };
  if (isAnd(b)) return { And: [a, ...b.And] };
  return { And: [a, b] };
}

export function reduceOr<T>(a: Condition<T>, b: Condition<T>): Condition<T> {
  if (isAlways(a)) return a;
  if (isNever(a)) return b;
  if (isAlways(b)) return b;
  if (isNever(b)) return a;
  if (isOr(a) && isOr(b)) return { Or: [...a.Or, ...b.Or] };
  if (isOr(a)) return { Or: [...a.Or, b] };
  if (isOr(b)) return { Or: [a, ...b.Or] };
  return { Or: [a, b] };
}
