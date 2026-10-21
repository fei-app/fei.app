// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "FEI",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
    ],
    products: [
        .library(name: "FEI", targets: ["FEI"]),
    ],
    targets: [
        .target(name: "FEI"),
    ]
)
