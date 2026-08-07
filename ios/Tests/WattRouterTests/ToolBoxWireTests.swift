// ToolBoxWireTests.swift — describing the tools to the model.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// The assertions go through `JSONSerialization` rather than matching text,
// because what matters is the structure the provider parses and not how it was
// spelled. Matching a string would break on a key order nobody cares about and
// pass on a nesting mistake that matters.

import Foundation
import XCTest

@testable import WattRouter

final class ToolBoxWireTests: XCTestCase {
    private struct Stub: Tool {
        let name: String
        var purpose = "Do a thing."
        var schema = #"{"type":"object","properties":{"path":{"type":"string"}}}"#
        func run(arguments: Data) async throws -> String { "" }
    }

    /// The definitions, parsed back into something to assert against.
    private func parsed(_ box: ToolBox) throws -> [[String: Any]] {
        let data = Data(try box.definitions().utf8)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [[String: Any]])
    }

    func testAToolArrivesInTheShapeTheProviderExpects() throws {
        let entries = try parsed(ToolBox([Stub(name: "read_file")]))
        let entry = try XCTUnwrap(entries.first)

        XCTAssertEqual(entry["type"] as? String, "function")
        let function = try XCTUnwrap(entry["function"] as? [String: Any])
        XCTAssertEqual(function["name"] as? String, "read_file")
        XCTAssertEqual(function["description"] as? String, "Do a thing.")

        // The schema is nested as an object, not as the string it is stored as.
        // Passed through verbatim it would reach the provider quoted, and every
        // tool would appear to take one argument called "a JSON string".
        let parameters = try XCTUnwrap(function["parameters"] as? [String: Any])
        XCTAssertEqual(parameters["type"] as? String, "object")
        XCTAssertNotNil(parameters["properties"] as? [String: Any])
    }

    func testTheOrderIsTheOneTheToolsWereGivenIn() throws {
        let box = ToolBox([Stub(name: "first"), Stub(name: "second"), Stub(name: "third")])
        let names = try parsed(box).compactMap { ($0["function"] as? [String: Any])?["name"] as? String }
        XCTAssertEqual(names, ["first", "second", "third"])
    }

    func testTheSameToolsProduceTheSameBytes() throws {
        // Keys are sorted so a prompt cache can hit and a diff is readable. A
        // dictionary's own order is not stable between runs.
        let box = ToolBox([Stub(name: "a"), Stub(name: "b")])
        XCTAssertEqual(try box.definitions(), try box.definitions())
    }

    func testASchemaThatIsNotJSONNamesTheToolThatOwnsIt() {
        // The whole reason this can throw. A provider rejects the request as
        // malformed and names nothing; six tools in, that is a bisect.
        let box = ToolBox([Stub(name: "good"), Stub(name: "broken", schema: "{oops")])
        XCTAssertThrowsError(try box.definitions()) { error in
            XCTAssertEqual(
                error as? ToolBox.SchemaError,
                ToolBox.SchemaError(tool: "broken", detail: "schema is not valid JSON"))
        }
    }

    func testValidJSONThatIsNotAnObjectIsStillWrong() {
        // `parameters` has to be a schema object. These both parse, and both
        // would be rejected on the wire as part of a request naming nothing.
        for schema in ["[]", #""just a string""#, "42"] {
            let box = ToolBox([Stub(name: "odd", schema: schema)])
            XCTAssertThrowsError(try box.definitions(), schema) { error in
                XCTAssertEqual(
                    error as? ToolBox.SchemaError,
                    ToolBox.SchemaError(tool: "odd", detail: "schema is not a JSON object"))
            }
        }
    }

    func testNoToolsIsAnEmptyArrayRatherThanNothing() throws {
        // A turn with no tools still has to produce something the provider will
        // parse if a caller sends the field at all.
        XCTAssertEqual(try ToolBox([]).definitions(), "[]")
    }
}
