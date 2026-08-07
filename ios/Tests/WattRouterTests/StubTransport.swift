// StubTransport.swift — a provider that answers from a script.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Contents
//   StubTransport  A `URLProtocol` that replies with whatever it is told to.
//
// Installed on a `URLSessionConfiguration`, this replaces the network *below*
// `URLSession`, so the client under test is the real one driving the real session
// machinery and only the socket is imaginary. That matters here: what the tests
// check is partly how `URLSession` itself behaves — when it hands bytes over —
// and a hand-rolled fake session would prove nothing about that.

import Foundation

/// A transport that answers from a script.
final class StubTransport: URLProtocol {
    /// What the next request gets back. `perChunk` is waited before each piece,
    /// so a caller can be seen receiving them separately rather than all at once;
    /// `transportError` fails before any response head, as a lost network does.
    struct Script {
        var status = 200
        var chunks: [String] = []
        var perChunk: TimeInterval = 0
        var transportError: URLError?
    }

    /// Set before a call and read inside it. Unsynchronised on purpose: XCTest
    /// runs a class's methods one at a time, and a lock would claim a concurrency
    /// these tests have not got.
    nonisolated(unsafe) static var script = Script()

    /// What the client actually put on the wire, for the tests that check it.
    nonisolated(unsafe) static var sent: (headers: [String: String], body: Data)?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    override func startLoading() {
        Self.sent = (request.allHTTPHeaderFields ?? [:], Self.body(of: request))
        let script = Self.script

        if let error = script.transportError {
            client?.urlProtocol(self, didFailWithError: error)
            return
        }

        guard let url = request.url,
            let response = HTTPURLResponse(
                url: url, statusCode: script.status, httpVersion: "HTTP/1.1",
                headerFields: ["Content-Type": "text/event-stream"])
        else { return }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)

        // Synchronously, blocking this thread: `startLoading` already runs off the
        // caller's, and one request at a time is all these tests make.
        for chunk in script.chunks {
            if script.perChunk > 0 { Thread.sleep(forTimeInterval: script.perChunk) }
            client?.urlProtocol(self, didLoad: Data(chunk.utf8))
        }
        client?.urlProtocolDidFinishLoading(self)
    }

    /// The request body, wherever it ended up.
    ///
    /// A POST reaches a `URLProtocol` with its body moved to `httpBodyStream`;
    /// `httpBody` is nil by the time it gets here. Read only `httpBody` and this
    /// returns empty, and an assertion about what was sent then passes against a
    /// request nobody ever inspected.
    static func body(of request: URLRequest) -> Data {
        if let data = request.httpBody { return data }
        guard let stream = request.httpBodyStream else { return Data() }

        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let read = stream.read(&buffer, maxLength: buffer.count)
            if read <= 0 { break }
            data.append(contentsOf: buffer[..<read])
        }
        return data
    }
}
