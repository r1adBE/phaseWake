// swift-tools-version: 5.9
import PackageDescription

// Plattformneutraler Kern der Phasenerkennung. Die iPhone- und die Watch-App binden ihn ein;
// die Regel ist identisch mit android/core (siehe docs/PHASENERKENNUNG.md).
let package = Package(
    name: "PhaseWakeCore",
    platforms: [.iOS(.v17), .watchOS(.v10), .macOS(.v14)],
    products: [
        .library(name: "PhaseWakeCore", targets: ["PhaseWakeCore"]),
    ],
    targets: [
        .target(name: "PhaseWakeCore"),
        .testTarget(name: "PhaseWakeCoreTests", dependencies: ["PhaseWakeCore"]),
    ]
)
