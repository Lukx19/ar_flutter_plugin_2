// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "VisibilityGridCoreValidation",
    targets: [
        .target(
            name: "ar_flutter_plugin_2",
            path: "Classes",
            exclude: [
                "ArFlutterPlugin.m",
                "ArModelBuilder.swift",
                "CloudAnchorHandler.swift",
                "IosARView.swift",
                "IosARViewFactory.swift",
                "JWTGenerator.swift",
                "SceneDepthAdapter.swift",
                "Serialization",
                "SwiftArFlutterPlugin.swift",
                "VisibilityGridChannel.swift",
                "VisibilityGridRenderer.swift"
            ],
            sources: [
                "VisibilityGridAssociation.swift",
                "VisibilityGridCore.swift",
                "VisibilityGridTypes.swift",
                "M0PortableReference.swift"
            ]
        ),
        .testTarget(
            name: "VisibilityGridCoreTests",
            dependencies: ["ar_flutter_plugin_2"],
            path: "Tests",
            exclude: ["VisibilityGridDepthRendererLifecycleTests.swift"],
            sources: [
                "VisibilityGridCoreTests.swift",
                "VisibilityGridDepthCoreTests.swift",
                "M0PortableReferenceTests.swift"
            ]
        )
    ],
    swiftLanguageVersions: [.v5]
)
