import XCTest
import BatteriesClient
import KhrysalisRuntime

class Tests: XCTestCase {
    
    override func setUp() {
        super.setUp()
        let _ = ExampleFields.INSTANCE
    }
    
    override func tearDown() {
        // Put teardown code here. This method is called after the invocation of each test method in the class.
        super.tearDown()
    }
    
    func testFullQuery() {
        let instance = Query(Example.chain().get(prop: ExampleFields.INSTANCE.x).eq("Hello!"))
        print(instance.toJsonString())
        print(instance.toJsonString().fromJsonString() as Query<Example>?)
    }
    
    func testQuickCondition() {
        let instance = Example.chain().get(prop: ExampleFields.INSTANCE.x).eq("Hello!")
        print(instance.toJsonString())
        print(instance.toJsonString().fromJsonString() as Condition<Example>?)
    }
    
    func testQuickModification() {
        let instance = Example.chain().get(prop: ExampleFields.INSTANCE.x).assign("New Value")
        print(instance.toJsonString())
        print(instance.toJsonString().fromJsonString() as Modification<Example>?)
    }
    
    func testQuickSort() {
        let instance = [SortPart(field: ExampleFields.INSTANCE.x), SortPart(field: ExampleFields.INSTANCE.x, ascending: false)]
        print(instance.toJsonString())
        print(instance.toJsonString().fromJsonString() as Array<SortPart<Example>>?)
    }
    
    func testPerformanceExample() {
        // This is an example of a performance test case.
        self.measure() {
            // Put the code you want to measure the time of here.
        }
    }
    
}

struct Example: Codable, Hashable {
    var x: String
}

class ExampleFields {
    static let INSTANCE = ExampleFields()
    
    let x = DataClassProperty<Example, String>(name: "x", get: { $0.x }, set: { i, o in
        var copy = i
        copy.x = o
        return copy
    }, compare: compareBy { $0.x })
    
    init() {
        registerDataClassProperties(type: Example.self, properties: ["x": x])
    }
}

extension Example {
    static func chain() -> PropChain<Example, Example> {
        return startChain()
    }
}
