import ARKit
import CoreVideo
import Foundation
import UIKit

enum SceneDepthAcquisition {
    case observation(DepthObservation)
    case transientlyUnavailable
    case failure(String)
}

enum SceneDepthAdapter {
    static func orientedDimensions(
        width: Int,
        height: Int,
        orientation: UIInterfaceOrientation
    ) -> [Int] {
        switch orientation {
        case .portrait, .portraitUpsideDown:
            return [height, width]
        default:
            return [width, height]
        }
    }

    static func orientedPixel(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        orientation: UIInterfaceOrientation
    ) -> [Int] {
        switch orientation {
        case .portrait:
            return [y, width - 1 - x]
        case .portraitUpsideDown:
            return [height - 1 - y, x]
        case .landscapeLeft:
            return [width - 1 - x, height - 1 - y]
        default:
            return [x, y]
        }
    }

    static func accepts(
        confidence: SceneDepthConfidence,
        minimum: SceneDepthConfidence
    ) -> Bool {
        confidence.rawValue >= minimum.rawValue
    }

    static func mode(
        capabilitySupported: Bool,
        sceneDepthAvailable: Bool
    ) -> SceneDepthMode {
        guard capabilitySupported else { return .featureOnly }
        return sceneDepthAvailable
            ? .sceneDepth
            : .sceneDepthTransientlyUnavailable
    }

    @available(iOS 14.0, *)
    static func acquire(
        frame: ARFrame,
        groupGeneration: Int64,
        sessionGeneration: Int64,
        configuration: VisibilityGridDepthConfiguration
    ) -> SceneDepthAcquisition {
        guard let sceneDepth = frame.sceneDepth else {
            return .transientlyUnavailable
        }
        let depthBuffer = sceneDepth.depthMap
        guard let confidenceBuffer = sceneDepth.confidenceMap else {
            return .transientlyUnavailable
        }
        guard CVPixelBufferGetPixelFormatType(depthBuffer) ==
                kCVPixelFormatType_DepthFloat32,
            CVPixelBufferGetPixelFormatType(confidenceBuffer) ==
                kCVPixelFormatType_OneComponent8
        else {
            return .failure("Unsupported ARKit scene-depth pixel format")
        }

        let depthLock = CVPixelBufferLockBaseAddress(
            depthBuffer,
            .readOnly
        )
        guard depthLock == kCVReturnSuccess else {
            return .failure("Unable to lock ARKit scene-depth buffer")
        }
        defer {
            CVPixelBufferUnlockBaseAddress(depthBuffer, .readOnly)
        }
        let confidenceLock = CVPixelBufferLockBaseAddress(
            confidenceBuffer,
            .readOnly
        )
        guard confidenceLock == kCVReturnSuccess else {
            return .failure("Unable to lock ARKit depth-confidence buffer")
        }
        defer {
            CVPixelBufferUnlockBaseAddress(confidenceBuffer, .readOnly)
        }

        let width = CVPixelBufferGetWidth(depthBuffer)
        let height = CVPixelBufferGetHeight(depthBuffer)
        guard width > 0, height > 0,
            width == CVPixelBufferGetWidth(confidenceBuffer),
            height == CVPixelBufferGetHeight(confidenceBuffer),
            let depthBase = CVPixelBufferGetBaseAddress(depthBuffer),
            let confidenceBase =
                CVPixelBufferGetBaseAddress(confidenceBuffer)
        else {
            return .failure("Invalid ARKit scene-depth buffers")
        }

        let depthStride =
            CVPixelBufferGetBytesPerRow(depthBuffer) /
            MemoryLayout<Float32>.stride
        let confidenceStride =
            CVPixelBufferGetBytesPerRow(confidenceBuffer)
        let depthValues = depthBase.assumingMemoryBound(to: Float32.self)
        let confidenceValues =
            confidenceBase.assumingMemoryBound(to: UInt8.self)
        let pixelCount = width * height
        let sampleStep = max(
            1,
            Int(
                ceil(
                    sqrt(
                        Double(pixelCount) /
                        Double(
                            configuration.maxAcceptedPixelsPerObservation
                        )
                    )
                )
            )
        )
        var samples: [DepthPixelSample] = []
        samples.reserveCapacity(
            min(
                pixelCount,
                configuration.maxAcceptedPixelsPerObservation
            )
        )
        var rejected = 0
        for y in stride(from: 0, to: height, by: sampleStep) {
            for x in stride(from: 0, to: width, by: sampleStep) {
                if samples.count >=
                    configuration.maxAcceptedPixelsPerObservation {
                    break
                }
                let depth = Double(depthValues[y * depthStride + x])
                let rawConfidence =
                    confidenceValues[y * confidenceStride + x]
                let confidence = normalizedConfidence(rawConfidence)
                guard depth.isFinite,
                    depth >= configuration.minimumDepthMeters,
                    depth <= configuration.maximumDepthMeters,
                    confidence >= configuration.minimumConfidence
                else {
                    rejected += 1
                    continue
                }
                samples.append(
                    DepthPixelSample(
                        x: x,
                        y: y,
                        depthMeters: depth,
                        confidence: confidence
                    )
                )
            }
        }

        let imageResolution = frame.camera.imageResolution
        guard imageResolution.width > 0, imageResolution.height > 0 else {
            return .failure("Invalid ARKit camera image resolution")
        }
        let intrinsics = frame.camera.intrinsics
        let scaleX = Double(width) / Double(imageResolution.width)
        let scaleY = Double(height) / Double(imageResolution.height)
        return .observation(
            DepthObservation(
                timestampNanoseconds:
                    max(0, Int64(frame.timestamp * 1_000_000_000)),
                groupGeneration: groupGeneration,
                sessionGeneration: sessionGeneration,
                tracking: frame.camera.trackingState.isVisibilityGridTracking,
                width: width,
                height: height,
                samples: samples,
                sourceRejectedPixels: rejected,
                intrinsics: DepthIntrinsics(
                    fx: Double(intrinsics[0][0]) * scaleX,
                    fy: Double(intrinsics[1][1]) * scaleY,
                    cx: Double(intrinsics[2][0]) * scaleX,
                    cy: Double(intrinsics[2][1]) * scaleY
                ),
                worldFromCameraGL:
                    frame.camera.transform.visibilityGridDoubles
            )
        )
    }

    private static func normalizedConfidence(_ rawValue: UInt8) -> UInt8 {
        switch rawValue {
        case UInt8(SceneDepthConfidence.high.rawValue):
            return 255
        case UInt8(SceneDepthConfidence.medium.rawValue):
            return 128
        default:
            return 0
        }
    }
}

private extension ARCamera.TrackingState {
    var isVisibilityGridTracking: Bool {
        if case .normal = self { return true }
        return false
    }
}

extension simd_float4x4 {
    var visibilityGridDoubles: [Double] {
        [
            Double(columns.0.x), Double(columns.0.y),
            Double(columns.0.z), Double(columns.0.w),
            Double(columns.1.x), Double(columns.1.y),
            Double(columns.1.z), Double(columns.1.w),
            Double(columns.2.x), Double(columns.2.y),
            Double(columns.2.z), Double(columns.2.w),
            Double(columns.3.x), Double(columns.3.y),
            Double(columns.3.z), Double(columns.3.w)
        ]
    }
}
