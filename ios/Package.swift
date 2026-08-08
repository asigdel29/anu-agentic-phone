// swift-tools-version: 6.0
//
// Package.swift — the routing core, as a Swift package.
//
// History
//   2026-08-06  A. Sigdel  Created.
//   2026-08-08  A. Sigdel  Named the two system libraries libgit2 leaves
//                          undefined.
//
// The binary target is build output, not source: run scripts/build-ios-core.sh
// before building this package. See .gitignore for why it is not checked in.

import PackageDescription

let package = Package(
    name: "WattRouter",
    // The core has no OS dependencies, but the plan's tier map assumes a phone
    // whose memory limit entitlement exists from 17 onwards.
    platforms: [.iOS(.v17)],
    products: [
        .library(name: "WattRouter", targets: ["WattRouter"])
    ],
    targets: [
        // The name matches the module map in router/include. Changing one
        // without the other fails the import with a message naming neither.
        .binaryTarget(name: "WattRouterFFI", path: "WattRouterFFI.xcframework"),
        // zlib and iconv are libgit2's, left undefined for whoever links it. A
        // static archive carries no such list, so the absence reads as forty
        // undefined symbols named `_inflate` and `_iconv_open`, which say
        // nothing about git. Both ship with iOS; neither is a package to add.
        .target(
            name: "WattRouter",
            dependencies: ["WattRouterFFI"],
            linkerSettings: [
                .linkedLibrary("z"),
                .linkedLibrary("iconv"),
            ]),
        .testTarget(name: "WattRouterTests", dependencies: ["WattRouter"]),
    ]
)
