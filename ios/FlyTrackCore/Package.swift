// swift-tools-version: 6.2
// FlyTrackCore is pure Foundation so `swift test` runs on Linux.
// The SwiftUI app (../FlyTrack) is the only target that imports Apple UI frameworks.
import PackageDescription

let package = Package(
    name: "FlyTrackCore",
    platforms: [
        .iOS("26.0"),
        .macOS("14.0"),
    ],
    products: [
        .library(name: "FlyTrackCore", targets: ["FlyTrackCore"]),
    ],
    targets: [
        .target(name: "FlyTrackCore"),
        .testTarget(
            name: "FlyTrackCoreTests",
            dependencies: ["FlyTrackCore"],
            resources: [.copy("Fixtures")]
        ),
    ]
)
