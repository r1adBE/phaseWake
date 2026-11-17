// swift-tools-version: 5.9
import PackageDescription

// Strikte Prüfung der Nebenläufigkeit wie in den App-Targets (SWIFT_STRICT_CONCURRENCY = complete), im Swift-5-Modus:
// Befunde sind Warnungen, kein Abbruch. Mit swift-tools-version 5.9 heisst die Einstellung «experimentell».
let strictConcurrency: [SwiftSetting] = [.enableExperimentalFeature("StrictConcurrency")]

// Plattformneutraler Kern der Phasenerkennung. Die iPhone- und die Watch-App binden ihn ein;
// die Regel ist identisch mit android/core (siehe docs/PHASENERKENNUNG.md).
let package = Package(
    name: "PhaseWakeCore",
    platforms: [.iOS(.v17), .watchOS(.v10), .macOS(.v14)],
    products: [
        .library(name: "PhaseWakeCore", targets: ["PhaseWakeCore"]),
    ],
    targets: [
        .target(name: "PhaseWakeCore", swiftSettings: strictConcurrency),
        .testTarget(name: "PhaseWakeCoreTests", dependencies: ["PhaseWakeCore"], swiftSettings: strictConcurrency),
    ]
)
