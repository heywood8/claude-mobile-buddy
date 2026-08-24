// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "cmbridge",
    platforms: [.macOS(.v13)],
    dependencies: [
        .package(url: "https://github.com/apple/swift-nio.git", from: "2.65.0"),
    ],
    targets: [
        .executableTarget(
            name: "cmbridge",
            dependencies: [
                .product(name: "NIOCore", package: "swift-nio"),
                .product(name: "NIOPosix", package: "swift-nio"),
                .product(name: "NIOHTTP1", package: "swift-nio"),
            ],
            // Swift 5 language mode. CoreBluetooth's delegate model and NIO's channel handlers
            // both predate strict concurrency checking, and in Swift 6 mode the region-based
            // isolation checker currently fails outright on the pattern in HookServer.
            // Migrating is tracked in backlog.md.
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
    ]
)
