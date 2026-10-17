import ARKit
import Foundation
import simd
import XCTest
@testable import ar_flutter_plugin_2

/// The canonical camera frame (`opencv_c2w_v1`) on the iOS playback path:
/// the first frame of the recorded `room2_trimmed` session goes through the
/// production pose stream conversion (`PoseWireSample(cameraTransform:)`, as
/// `PoseStreamObserver` does for every playback frame, then
/// `PoseBatchWire.encode`) and the decoded OpenCV pose must satisfy the
/// invariants of `FrameConventionGoldenTests`: a proper rotation, the tracking
/// pose with camera Y and Z negated, a point ahead at positive depth on the
/// principal point, and image y growing downward (the recorded camera's up
/// axis is image up).
final class RecordedFrameConventionTests: XCTestCase {
  static func readFloat(_ data: Data, _ offset: Int) -> Float {
    var bits: UInt32 = 0
    for i in 0..<4 { bits |= UInt32(data[data.startIndex + offset + i]) << UInt32(i * 8) }
    return Float(bitPattern: bits)
  }

  func testFirstRecordedPoseIsCanonicalOnThePlaybackPath() throws {
    let session = try RecordedSession(directory: try RecordedSessionFixture.room2URL())
    let record = try session.frame(at: 0)
    let sample = PoseWireSample(
      sequence: 1,
      systemTimestampMs: 0,
      timestampNs: PoseWireSample.nanoseconds(record.timestamp),
      cameraTransform: record.cameraTransform,
      trackingState: record.trackingState)
    let encoded = PoseBatchWire.encode(sample)
    let openCv = (0..<16).map { Self.readFloat(encoded, 80 + $0 * 4) }
    let tracking = (0..<16).map { Self.readFloat(encoded, 172 + $0 * 4) }

    // The tracking pose is the recorded ARKit transform, bit for bit.
    let recorded = [record.cameraTransform.columns.0, record.cameraTransform.columns.1,
                    record.cameraTransform.columns.2, record.cameraTransform.columns.3]
      .flatMap { [$0.x, $0.y, $0.z, $0.w] }
    XCTAssertEqual(tracking.map(\.bitPattern), recorded.map(\.bitPattern))
    // T_world_cam = T_world_arcam * diag(1, -1, -1, 1).
    for column in 0..<4 {
      for row in 0..<4 {
        let sign: Float = column == 1 || column == 2 ? -1 : 1
        XCTAssertEqual(openCv[column * 4 + row].bitPattern, (sign * tracking[column * 4 + row]).bitPattern)
      }
    }

    let t = openCv.map(Double.init)
    let x = SIMD3(t[0], t[1], t[2]), y = SIMD3(t[4], t[5], t[6]), z = SIMD3(t[8], t[9], t[10])
    for (a, b, expected) in [(x, x, 1.0), (y, y, 1.0), (z, z, 1.0), (x, y, 0.0), (x, z, 0.0), (y, z, 0.0)] {
      XCTAssertEqual(simd_dot(a, b), expected, accuracy: 1e-4)
    }
    XCTAssertEqual(simd_dot(simd_cross(x, y), z), 1, accuracy: 1e-4, "right-handed proper rotation")

    let k = record.intrinsics
    let fx = Double(k[0][0]), fy = Double(k[1][1]), cx = Double(k[2][0]), cy = Double(k[2][1])
    let centre = SIMD3(t[12], t[13], t[14])
    func project(_ p: SIMD3<Double>) -> (SIMD3<Double>, SIMD2<Double>) {
      let d = p - centre
      let camera = SIMD3(simd_dot(x, d), simd_dot(y, d), simd_dot(z, d))
      return (camera, SIMD2(fx * camera.x / camera.z + cx, fy * camera.y / camera.z + cy))
    }
    let (ahead, aheadPixel) = project(centre + 2 * z)
    XCTAssertEqual(ahead.z, 2, accuracy: 1e-4, "a point ahead has positive depth")
    XCTAssertEqual(aheadPixel.x, cx, accuracy: 1e-2)
    XCTAssertEqual(aheadPixel.y, cy, accuracy: 1e-2)
    let (behind, _) = project(centre - z)
    XCTAssertLessThan(behind.z, 0, "a point behind has negative depth")
    // ARKit camera +Y (tracking column 1) is up in the sensor image.
    let trackingUp = SIMD3(Double(tracking[4]), Double(tracking[5]), Double(tracking[6]))
    let (_, upPixel) = project(centre + 2 * z + 0.25 * trackingUp)
    XCTAssertLessThan(upPixel.y, cy, "image y grows downward")
    XCTAssertEqual(upPixel.x, cx, accuracy: 1e-2)
  }
}
