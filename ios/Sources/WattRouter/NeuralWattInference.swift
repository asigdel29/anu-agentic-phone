// NeuralWattInference.swift — the one call that leaves the device.
//
// History
//   2026-08-07  A. Sigdel  Created.
//
// Contents
//   NeuralWattInference  Ask a remote model over HTTPS, without buffering.
//
// `router/src/upstream.rs` is the specification. The decision that matters most
// here is that the body is never buffered — `bytes(for:)`, never `data(for:)`.
// upstream.rs:9 gives the reason and it is why that module exists: buffering
// "would turn a streaming response into a batch one, and would do so silently —
// everything still works, only slower".
//
// The transport policy is next door, in URLSessionConfiguration+Upstream.swift,
// because which of the two timeouts is which is a trap worth its own file.

import Foundation

/// A model behind the provider's API, asked over one HTTPS request.
public struct NeuralWattInference: Inference {
    /// The provider, as `Config::from_env` defaults to it. Force-unwrapped and
    /// safe to be: a literal parses on every run or on none, and no input
    /// reaches it.
    public static let defaultBaseURL = URL(string: "https://api.neuralwatt.com/v1")!

    private let endpoint: URL
    private let apiKey: String
    private let session: URLSession

    /// - Parameters:
    ///   - apiKey: the provider credential, out of the Keychain on a phone. It is
    ///     the only secret the app holds.
    ///   - baseURL: where the API lives.
    ///   - session: the transport. The default builds one, so an app should keep
    ///     a single `NeuralWattInference` rather than make one per turn.
    public init(
        apiKey: String,
        baseURL: URL = defaultBaseURL,
        session: URLSession = URLSession(configuration: .upstream())
    ) {
        self.apiKey = apiKey
        self.endpoint = baseURL.appending(path: "chat/completions")
        self.session = session
    }

    public func complete(_ conversation: Conversation, model: String, maxTokens: Int?)
        -> AsyncThrowingStream<String, any Error>
    {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let (bytes, response) = try await session.bytes(
                        for: request(conversation, model: model, maxTokens: maxTokens))
                    try await Self.check(response, body: bytes, model: model)

                    for try await line in bytes.lines {
                        switch try ServerSentEvent.decoding(line) {
                        case .text(let text): continuation.yield(text)
                        case .done: return continuation.finish()
                        case .ignored: continue
                        }
                    }
                    // The body ended without `[DONE]`. What arrived is the answer;
                    // a provider closing early is not this layer's to second-guess.
                    continuation.finish()
                } catch let error as InferenceError {
                    continuation.finish(throwing: error)
                } catch let error as URLError where error.code == .cancelled {
                    // A cancelled session task surfaces as a `URLError`, not a
                    // `CancellationError`. Left as it arrives it reads as an
                    // unreachable model, and a chain would answer by dialling the
                    // next one on its way out of the door.
                    continuation.finish(throwing: CancellationError())
                } catch is CancellationError {
                    continuation.finish(throwing: CancellationError())
                } catch let error as URLError {
                    // Not `String(describing:)`, which on a `URLError` is a
                    // paragraph of session bookkeeping around the one sentence
                    // saying what happened.
                    continuation.finish(
                        throwing: InferenceError.unavailable(
                            model: model,
                            detail: "URLError \(error.code.rawValue): \(error.localizedDescription)"
                        ))
                } catch {
                    continuation.finish(
                        throwing: InferenceError.unavailable(
                            model: model, detail: String(describing: error)))
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Accept or refuse the response head, before a byte of the body is read.
    /// `Inference` promises that a failure with nothing delivered is still the
    /// chain's to retry, and a client yielding anything at all before this
    /// returns would take that choice away.
    ///
    /// - Throws: [`InferenceError`] IF the status is not a success. The body is
    ///   read only in that case, and only to quote what the provider said.
    private static func check(
        _ response: URLResponse, body: URLSession.AsyncBytes, model: String
    ) async throws {
        guard let http = response as? HTTPURLResponse else {
            throw InferenceError.unavailable(model: model, detail: "not an HTTP response")
        }
        switch InferenceError.disposition(ofStatus: http.statusCode) {
        case .answer:
            return
        case .stop:
            throw InferenceError.rejected(
                model: model, status: http.statusCode, detail: await explanation(from: body))
        case .retry:
            throw InferenceError.unavailable(
                model: model, detail: "HTTP \(http.statusCode): \(await explanation(from: body))")
        }
    }

    /// The start of a failed response's body, where the provider says what was
    /// wrong. Without it a 400 is a status and nothing else — every model in a
    /// chain refused, and no way to find out whether the fault was the request,
    /// the credential, or the account.
    ///
    /// Bounded, because this body was already going to be discarded and reading
    /// all of one to print it is a way to be hurt by an upstream having a bad day.
    /// A truncated explanation is still an explanation.
    private static func explanation(
        from body: URLSession.AsyncBytes, limit: Int = 1024
    ) async -> String {
        var collected = Data()
        do {
            for try await byte in body {
                collected.append(byte)
                if collected.count >= limit { break }
            }
        } catch {
            // A body that failed midway is still the best account available, and
            // the status was the finding in any case.
        }
        let text = String(decoding: collected, as: UTF8.self)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty ? "no message" : text
    }

    private func request(_ conversation: Conversation, model: String, maxTokens: Int?) throws
        -> URLRequest
    {
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        request.httpBody = try JSONEncoder().encode(
            Request(model: model, messages: conversation.messages, maxTokens: maxTokens))
        return request
    }

    /// The body the provider expects.
    ///
    /// Deliberately not `Conversation.requestBody`. That one is what the routing
    /// core classifies, and it carries `x_wattrouter_background` — a key whose
    /// whole purpose is to influence a decision already made by the time this
    /// runs. What the two do share is `Message`, so the part that could drift
    /// from the wire is not written twice.
    private struct Request: Encodable {
        let model: String
        let messages: [Message]
        let maxTokens: Int?
        /// Always. This client has no non-streaming path, by design.
        let stream = true

        enum CodingKeys: String, CodingKey {
            case model, messages, stream
            case maxTokens = "max_tokens"
        }
    }
}
