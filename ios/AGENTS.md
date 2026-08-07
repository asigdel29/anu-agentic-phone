# Working in `ios/`

The phone app, and the routing core wrapped for Swift. Read the root `AGENTS.md` first; this
holds what is true only here.

This is the one subtree that **cannot be fully verified on a machine without Xcode**, and most
of what follows is about telling apart what you have checked from what you have assumed.

## Layout

`Sources/WattRouter/` is the library: `Router.swift` is the core as Swift sees it,
`Conversation.swift` the state a turn accumulates, `Inference.swift` the seam to a model,
`ChainWalk.swift` trying each model in a chain until one answers, `Agent.swift` the turn loop,
and `ToolBox.swift` with the six tools plus `Workspace.swift` deciding which files they may
touch. `Tests/WattRouterTests/` mirrors it. `App/` is the application target.

`Package.swift` targets iOS 17 and depends on a binary target, `WattRouterFFI.xcframework`,
which is **build output and not checked in**. `just ios-core` produces it and needs Xcode.
`just ios-project` regenerates `WattRouter.xcodeproj` from `project.yml`; the project is build
output too.

## Verifying without Xcode

`swift build` on this package cannot work: the xcframework is absent, the platform is iOS, and
`import XCTest` fails outright because XCTest ships with Xcode rather than with the Command
Line Tools.

What does work is a scratch package under a temporary directory: a macOS SwiftPM package
holding the source files under test, plus a stub `XCTest` module supplying `XCTestCase` and the
assertions actually used. Copy in only the files the case needs — most of the library does not
reach `Router.swift` and so does not need the FFI.

Two things to get right in the stub, learned the hard way. Import nothing in it: a stub that
imports Foundation re-exports it and hides exactly the fault described below. And match the
real assertion signatures — `XCTAssertEqual` takes throwing autoclosures and does *not*
rethrow, so `XCTAssertEqual(try f(), x)` needs no outer `try`. A stub declared `rethrows`
forces a spurious one and sends you looking for a bug in the code under test.

SwiftUI is present in the macOS SDK, so a view compiles in a scratch package even without
Xcode. Compiling is not rendering, and a pull request should say which it did.

## Imports are per file, and XCTest hides that

A test file using a Foundation type — `JSONEncoder`, `Data`, `setenv` — must write
`import Foundation` itself. `import XCTest` pulls Foundation in transitively, so omitting it
compiles anyway and the file carries a dependency it never declared. It has happened three
times. The order here is `import Foundation`, then `import XCTest`, then `@testable import
WattRouter`.

## Saying what ran

`just ios-test` runs the suite on the shared simulator and needs `just ios-core` first. When
neither could run, say so in the pull request and name what you did instead. "Six checks in a
scratch package, no simulator" is a claim a reviewer can weigh; "tests pass" is not.
