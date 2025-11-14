"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.simplifyCondition = void 0;
function isAnd(c) {
    return "And" in c;
}
function isOr(c) {
    return "Or" in c;
}
function isNever(c) {
    return "Never" in c;
}
function isAlways(c) {
    return "Always" in c;
}
function isInside(c) {
    return "Inside" in c;
}
function isNotInside(c) {
    return "NotInside" in c;
}
function stringIsField(c) {
    if (!c)
        return false;
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
function getFieldKey(c) {
    const fieldKey = Object.keys(c).find((k) => stringIsField(k));
    if (fieldKey) {
        return fieldKey;
    }
    return null;
}
function simplifyCondition(condition) {
    if (isAnd(condition)) {
        const groups = new Map();
        for (const sub of condition.And) {
            for (const [path, subCond] of Object.entries(andByField(sub))) {
                if (!groups.has(path))
                    groups.set(path, []);
                groups.get(path).push(...subCond);
            }
        }
        const simplified = Array.from(groups.entries())
            .map(([p1, list]) => {
            const reduced = list.reduce((a, b) => reduceAnd(a, b));
            const final = finalSimplify(reduced);
            if (isAlways(final))
                return null;
            if (isNever(final))
                return { Never: true };
            const pathArr = p1 ? p1.split(".") : [];
            if (pathArr.length > 0) {
                return buildNested(pathArr, final);
            }
            return final;
        })
            .filter(Boolean);
        if (simplified.length === 0)
            return { Always: true };
        if (simplified.length === 1)
            return simplified[0];
        return { And: simplified };
    }
    else if (isOr(condition)) {
        const groups = new Map();
        for (const sub of condition.Or) {
            for (const [path, subCond] of Object.entries(orByField(sub))) {
                if (!groups.has(path))
                    groups.set(path, []);
                groups.get(path).push(...subCond);
            }
        }
        const simplified = Array.from(groups.entries())
            .map(([p1, list]) => {
            const reduced = list.reduce((a, b) => reduceOr(a, b));
            const final = finalSimplify(reduced);
            if (isNever(final))
                return null;
            if (isAlways(final))
                return { Always: true };
            const pathArr = p1 ? p1.split(".") : [];
            if (pathArr.length > 0) {
                return buildNested(pathArr, final);
            }
            return final;
        })
            .filter(Boolean);
        if (simplified.length === 0)
            return { Never: true };
        if (simplified.length === 1)
            return simplified[0];
        return { Or: simplified };
    }
    const field = getFieldKey(condition);
    if (field) {
        const simp = finalSimplify(condition[field]);
        if (isAlways(simp) || isNever(simp)) {
            return simp;
        }
        return { [field]: simp };
    }
    else {
        return finalSimplify(condition);
    }
}
exports.simplifyCondition = simplifyCondition;
function finalSimplify(cond) {
    if (isAnd(cond)) {
        if (cond.And.some(isNever))
            return { Never: true };
    }
    else if (isOr(cond)) {
        if (cond.Or.some(isAlways))
            return { Always: true };
    }
    else if (isInside(cond)) {
        if (cond.Inside.length === 0)
            return { Never: true };
    }
    else if (isNotInside(cond)) {
        if (cond.NotInside.length === 0)
            return { Always: true };
    }
    return cond;
}
function andByField(cond) {
    const result = {};
    function add(path, c) {
        if (!result[path])
            result[path] = [];
        result[path].push(c);
    }
    function walk(c, path) {
        if (isAnd(c)) {
            for (const sub of c.And)
                walk(sub, path);
            return;
        }
        const field = getFieldKey(c);
        if (field) {
            walk(c[field], [...path, field]);
            return;
        }
        const s = simplifyCondition(c);
        const sField = getFieldKey(s);
        if (sField) {
            walk(s[sField], [...path, sField]);
            return;
        }
        const key = path.join(".");
        add(key, s);
    }
    walk(cond, []);
    return result;
}
function orByField(cond) {
    const result = {};
    function add(path, c) {
        if (!result[path])
            result[path] = [];
        result[path].push(c);
    }
    function walk(c, path) {
        if (isOr(c)) {
            for (const sub of c.Or)
                walk(sub, path);
            return;
        }
        const field = getFieldKey(c);
        if (field) {
            walk(c[field], [...path, field]);
            return;
        }
        const s = simplifyCondition(c);
        const sField = getFieldKey(s);
        if (sField) {
            walk(s[sField], [...path, sField]);
            return;
        }
        const key = path.join(".");
        add(key, s);
    }
    walk(cond, []);
    return result;
}
function reduceAnd(a, b) {
    if (isAlways(a))
        return b;
    if (isNever(a))
        return a;
    if (isAlways(b))
        return a;
    if (isNever(b))
        return b;
    if (isAnd(a) && isAnd(b))
        return { And: [...a.And, ...b.And] };
    if (isAnd(a))
        return { And: [...a.And, b] };
    if (isAnd(b))
        return { And: [a, ...b.And] };
    return { And: [a, b] };
}
function reduceOr(a, b) {
    if (isAlways(a))
        return a;
    if (isNever(a))
        return b;
    if (isAlways(b))
        return b;
    if (isNever(b))
        return a;
    if (isOr(a) && isOr(b))
        return { Or: [...a.Or, ...b.Or] };
    if (isOr(a))
        return { Or: [...a.Or, b] };
    if (isOr(b))
        return { Or: [a, ...b.Or] };
    return { Or: [a, b] };
}
function buildNested(path, value) {
    if (path.length === 0)
        return value;
    const [head, ...tail] = path;
    return { [head]: buildNested(tail, value) };
}
//# sourceMappingURL=SimplifyCondition.js.map