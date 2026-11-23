// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "BeaverCore",
    platforms: [.iOS(.v18), .macOS(.v15)],
    products: [
        .library(name: "BeaverCore", targets: ["BeaverCore"]),
    ],
    targets: [
        .target(name: "BeaverCore"),
        .testTarget(name: "BeaverCoreTests", dependencies: ["BeaverCore"]),
    ],
    swiftLanguageModes: [.v6]
)
