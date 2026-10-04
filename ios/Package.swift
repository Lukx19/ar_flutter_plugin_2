// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "PluginDeclarationValidation",
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
                "Serialization",
                "SwiftArFlutterPlugin.swift"
            ],
            sources: [
                "CaptureIntentInterfaces.swift"
            ]
        )
    ],
    swiftLanguageVersions: [.v5]
)
