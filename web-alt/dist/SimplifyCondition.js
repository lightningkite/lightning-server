"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.reduceOr = exports.reduceAnd = exports.finalSimplify = exports.simplify = void 0;
function isAnd(c) {
    return "And" in c;
}
function isOr(c) {
    return "Or" in c;
}
function isOnField(c) {
    return "OnField" in c;
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
function simplify(condition) {
    if (isAnd(condition)) {
        const groups = new Map();
        for (const sub of condition.And) {
            for (const [path, subCond] of andByField(sub)) {
                console.log("Path: ", path);
                console.log("subCond: ", subCond);
                const key = path.map(p => p.name).join(".");
                if (!groups.has(key))
                    groups.set(key, []);
                groups.get(key).push(subCond);
            }
        }
        const simplified = Array.from(groups.entries()).map(([_, list]) => {
            const reduced = list.reduce((a, b) => reduceAnd(a, b));
            const final = finalSimplify(reduced);
            if (isAlways(final))
                return null;
            if (isNever(final))
                return { Never: true };
            return make(list.length ? [] : [], final);
        }).filter(Boolean);
        if (simplified.length === 0)
            return { Always: true };
        if (simplified.length === 1)
            return simplified[0];
        return { And: simplified };
    }
    else if (isOr(condition)) {
        const groups = new Map();
        for (const sub of condition.Or) {
            for (const [path, subCond] of orByField(sub)) {
                const key = path.map(p => p.name).join(".");
                if (!groups.has(key))
                    groups.set(key, []);
                groups.get(key).push(subCond);
            }
        }
        const simplified = Array.from(groups.entries()).map(([_, list]) => {
            const reduced = list.reduce((a, b) => reduceOr(a, b));
            const final = finalSimplify(reduced);
            if (isNever(final))
                return null;
            if (isAlways(final))
                return { Always: true };
            return make(list.length ? [] : [], final);
        }).filter(Boolean);
        if (simplified.length === 0)
            return { Never: true };
        if (simplified.length === 1)
            return simplified[0];
        return { Or: simplified };
    }
    else {
        return finalSimplify(condition);
    }
}
exports.simplify = simplify;
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
exports.finalSimplify = finalSimplify;
function andByField(cond) {
    if (isAnd(cond)) {
        return cond.And.flatMap(it => andByField(it));
    }
    else if (isOnField(cond)) {
        return andByField(cond.OnField.condition).map(([list, c]) => [[cond.OnField.key, ...list], c]);
    }
    else {
        const s = simplify(cond);
        if (isOnField(s)) {
            return andByField(s.OnField.condition).map(([list, c]) => [[s.OnField.key, ...list], c]);
        }
        return [[[], s]];
    }
}
function orByField(cond) {
    if (isOr(cond)) {
        return cond.Or.flatMap(it => orByField(it));
    }
    else if (isOnField(cond)) {
        return orByField(cond.OnField.condition).map(([list, c]) => [[cond.OnField.key, ...list], c]);
    }
    else {
        const s = simplify(cond);
        if (isOnField(s)) {
            return orByField(s.OnField.condition).map(([list, c]) => [[s.OnField.key, ...list], c]);
        }
        return [[[], s]];
    }
}
function make(props, cond) {
    if (props.length === 0)
        return cond;
    return {
        OnField: {
            // @ts-ignore
            key: props[0],
            condition: make(props.slice(1), cond),
        },
    };
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
exports.reduceAnd = reduceAnd;
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
exports.reduceOr = reduceOr;
//# sourceMappingURL=SimplifyCondition.js.map