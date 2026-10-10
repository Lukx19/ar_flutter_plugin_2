/// Status and error codes sent by the platform shells and the native marker
/// library. Native code sends only the numeric [code]; Dart maps each code to
/// a localized message. Codes are stable: never renumber, only append.
///
/// Ranges: 0 ok; 1-99 session and tracking; 100-199 depth and points;
/// 200-299 still capture and storage; 300-399 markers; 400-499 renderer;
/// 500-599 contract and transport.
enum NativeStatusCode {
  ok(0),
  trackingLost(1),
  trackingLimitedInsufficientLight(2),
  trackingLimitedExcessiveMotion(3),
  trackingLimitedInsufficientFeatures(4),
  arSessionRestarted(5),
  arSessionUnsupported(6),
  cameraPermissionDenied(7),
  cameraUnavailable(8),
  depthUnsupported(100),
  depthTemporarilyUnavailable(101),
  depthPoseMissing(102),
  depthStale(103),
  pointsNonFiniteDropped(104),
  pointsOnlyMode(105),
  photoSaveFailed(200),
  photoStorageFull(201),
  photoBlurRejected(202),
  markerDetectorUnavailable(300),
  markerRejectedSmall(301),
  markerRejectedBlur(302),
  markerRejectedOblique(303),
  markerMovedBetweenGroups(304),
  rendererUnavailable(400),
  rendererBufferFull(401),
  contractVersionMismatch(500),
  contractMessageMalformed(501),
  ingestQueueOverflow(502),
  recordingWriteFailed(503);

  const NativeStatusCode(this.code);

  /// Wire value (`u16`).
  final int code;

  /// Status for a wire value, or `null` for a code this build does not know.
  static NativeStatusCode? fromCode(int code) {
    for (final status in values) {
      if (status.code == code) return status;
    }
    return null;
  }
}
