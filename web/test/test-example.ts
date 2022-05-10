import {DataClass, JSON2, ReifiedType, setUpDataClass} from "@lightningkite/khrysalis-runtime";
import {Condition, PropChain, startChain, xPropChainEq} from "../src";


class Point implements DataClass {
    public static properties = ["x", "y"]
    public static propertyTypes = (): Record<string, ReifiedType> => ({x: [Number], y: [Number]})
    public constructor(public x: number, public y: number) { }
    copy: (values: Partial<this>) => this;
    equals: (other: any) => boolean;
    hashCode: () => number;
    toJSON: () => Record<string, any>;
    public static fromJSON: (record: Record<string, any>, innerType: Array<any>)=>Point;
}
setUpDataClass(Point)

class PointFields {
    static INSTANCE = new PointFields()
    readonly x = "x"
    readonly y = "y"
}
function PointChain(): PropChain<Point, Point> { return startChain() }
(Point as any)._fields = new Map(Object.entries({
    'x': PointFields.INSTANCE.x,
    'y': PointFields.INSTANCE.y,
}))

describe("TestDataClass", ()=> {
    test("hashCode", ()=> {
        console.log(Number)
        const cond = xPropChainEq(PointChain().get(PointFields.INSTANCE.x), 32)
        const asStr = JSON.stringify(cond)
        console.log(asStr)
        const copy = JSON2.parse(asStr, [Condition, [Point]])
        console.log(copy)
    })
})