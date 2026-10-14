import CoreGraphics
import ImageIO
import simd
import UniformTypeIdentifiers
import XCTest
@testable import ar_flutter_plugin_2

/// Laplacian sharpness against the Android `JpegBlurAnalyzer` row walk, and
/// still intrinsics scaled from the ARKit image resolution to the still.
final class StillQualityTests: XCTestCase {
  /// Fixture luma of the golden generator (a transcription of
  /// `JpegBlurAnalyzer.analyzeSampledRows`).
  private static func luma(_ x: Int, _ y: Int) -> UInt8 { UInt8((x * 37 + y * 101 + ((x * y) % 13) * 7) % 256) }

  private func score(width: Int, height: Int) -> LaplacianSharpness.Result? {
    LaplacianSharpness.analyzeSampledRows(width: width, height: height) { y, row in
      for x in 0..<width { row[x] = Self.luma(x, y) }
    }
  }

  func testRowWalkEqualsTheAndroidDefinition() throws {
    let small = try XCTUnwrap(score(width: 40, height: 30))
    XCTAssertEqual(small.score, 109151.70799123686, accuracy: 1e-9)
    XCTAssertEqual(small.sampledWidth, 40)
    XCTAssertEqual(small.sampledHeight, 30)
    let wide = try XCTUnwrap(score(width: 2100, height: 30))
    XCTAssertEqual(wide.score, 121237.98098074306, accuracy: 1e-9)
    XCTAssertEqual(wide.sampledWidth, 700)
    XCTAssertEqual(wide.sampledHeight, 10)
    XCTAssertNil(score(width: 2, height: 30))
  }

  func testSampleSizeKeepsTheLongEdgeAtMost1024() {
    XCTAssertEqual(LaplacianSharpness.sampleSize(width: 4032, height: 3024), 4)
    XCTAssertEqual(LaplacianSharpness.sampleSize(width: 1920, height: 1440), 2)
    XCTAssertEqual(LaplacianSharpness.sampleSize(width: 1024, height: 768), 1)
  }

  func testJpegScoreSeparatesSharpFromFlatImages() throws {
    let sharp = try XCTUnwrap(LaplacianSharpness.analyze(jpeg: Self.jpeg(width: 2016, height: 1512) { x, y in (x / 4 + y / 4) % 2 == 0 ? 255 : 0 }))
    let flat = try XCTUnwrap(LaplacianSharpness.analyze(jpeg: Self.jpeg(width: 2016, height: 1512) { _, _ in 128 }))
    XCTAssertEqual(sharp.sampledWidth, 1008)
    XCTAssertEqual(sharp.sampledHeight, 756)
    XCTAssertGreaterThan(sharp.score, LaplacianSharpness.productionThreshold)
    XCTAssertLessThan(flat.score, 1)
  }

  func testIntrinsicsScaleFromTheCameraImageToTheStill() throws {
    let k = simd_float3x3(simd_float3(1400, 0, 0), simd_float3(0, 1400, 0), simd_float3(960, 720, 1))
    let same = try XCTUnwrap(StillIntrinsics.scaled(k, imageResolution: CGSize(width: 1920, height: 1440), width: 1920, height: 1440))
    XCTAssertEqual(same, simd_float4(1400, 1400, 960, 720))
    let still = try XCTUnwrap(StillIntrinsics.scaled(k, imageResolution: CGSize(width: 1920, height: 1440), width: 4032, height: 3024))
    XCTAssertEqual(still.x, 2940, accuracy: 1e-3)
    XCTAssertEqual(still.y, 2940, accuracy: 1e-3)
    XCTAssertEqual(still.z, 2016, accuracy: 1e-3)
    XCTAssertEqual(still.w, 1512, accuracy: 1e-3)
    XCTAssertNil(StillIntrinsics.scaled(k, imageResolution: .zero, width: 4032, height: 3024))
  }

  func testCaptureConfigUsesTheAndroidBoundsAndRefusesRaw() {
    let base: [String: Any] = ["format": "jpeg", "resolution": ["width": 1920, "height": 1080], "jpegQuality": 95, "maxCacheSize": 10]
    XCTAssertEqual(try StillCaptureConfig.parse(base).jpegQuality, 95)
    var raw = base
    raw["format"] = "raw+jpeg"
    XCTAssertThrowsError(try StillCaptureConfig.parse(raw)) {
      XCTAssertEqual(($0 as? StillCaptureConfig.Invalid)?.code, "UNSUPPORTED_CAPTURE_FORMAT")
    }
    var quality = base
    quality["jpegQuality"] = 5
    XCTAssertThrowsError(try StillCaptureConfig.parse(quality)) { XCTAssertEqual(($0 as? StillCaptureConfig.Invalid)?.code, "CONFIG_INVALID") }
  }

  static func jpeg(width: Int, height: Int, pixel: (Int, Int) -> UInt8) -> Data {
    var gray = [UInt8](repeating: 0, count: width * height)
    for y in 0..<height { for x in 0..<width { gray[y * width + x] = pixel(x, y) } }
    let provider = CGDataProvider(data: Data(gray) as CFData)!
    let image = CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 8, bytesPerRow: width,
                        space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGBitmapInfo(rawValue: 0), provider: provider,
                        decode: nil, shouldInterpolate: false, intent: .defaultIntent)!
    let output = NSMutableData()
    let destination = CGImageDestinationCreateWithData(output, UTType.jpeg.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.95] as CFDictionary)
    CGImageDestinationFinalize(destination)
    return output as Data
  }
}
