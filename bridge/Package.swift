// swift-tools-version: 6.0
import PackageDescription

// Swift 5 language mode, declared at package level rather than per target: the per-target
// `.swiftLanguageMode` setting only exists from tools 6.2, and pinning the manifest that high
// would demand a Swift 6.2 toolchain from every CI runner for no benefit.
//
// Why Swift 5 mode at all: CoreBluetooth's delegate model and NIO's channel handlers both
// predate strict concurrency checking, and in Swift 6 mode the region-based isolation checker
// fails outright on the pattern in HookServer — with the compiler's own "please file a bug"
// diagnostic, not a fixable complaint about our code. Tracked in backlog.md.
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
            ]
        ),
        .testTarget(name: "cmbridgeTests", dependencies: ["cmbridge"]),
    ],
    swiftLanguageModes: [.v5]
)
