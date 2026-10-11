// Runs against a host build of the marker detector library (no OpenCV):
//
//   clang++ -std=c++17 -O2 -shared -DC3MD_WITH_OPENCV=0 \
//     -Inative/marker_detector/include -Inative/marker_detector/src \
//     native/marker_detector/src/marker_geometry.cpp \
//     native/marker_detector/src/marker_detector.cpp \
//     -o <dir>/capture3d_marker_detector.dll
//   CAPTURE3D_MARKER_DETECTOR_LIBRARY=<dir>/capture3d_marker_detector.dll \
//     flutter test test/marker_detection/marker_detector_ffi_test.dart
//
// Without the variable the tests are skipped.
import 'dart:ffi';
import 'dart:io';
import 'dart:math' as math;

import 'package:ar_flutter_plugin_2/marker_detection/marker_detector_ffi.dart';
import 'package:ar_flutter_plugin_2/visibility_grid_contract/visibility_grid_contract.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  final path = Platform.environment['CAPTURE3D_MARKER_DETECTOR_LIBRARY'];
  final skip = path == null
      ? 'set CAPTURE3D_MARKER_DETECTOR_LIBRARY to a host build'
      : null;

  test('binding opens the library and the detector lifecycle works', () {
    final lib = MarkerDetectorLibrary.open(path);
    expect(lib.abiVersion(), 1);
    expect(MarkerDetector.create(lib, 99), isNull);
    final d =
        MarkerDetector.create(lib, MarkerDetectorDictionary.arucoMip36h12)!;
    expect(
        d.setCamera(1500, 1500, 959.5, 539.5,
            distortion: [0.08, -0.05, 0, 0, 0]),
        MarkerDetectorStatus.ok);
    expect(d.setCamera(0, 1500, 959.5, 539.5), MarkerDetectorStatus.argument);
    expect(d.setMarkerSize(0.1), MarkerDetectorStatus.ok);
    expect(d.setMarkerSizeForId(5000, 0.1), MarkerDetectorStatus.argument);
    final image = d.imageBuffer(640 * 480)..fillRange(0, 640 * 480, 128);
    expect(image.length, 640 * 480);
    final expected = d.available ? 0 : MarkerDetectorStatus.unavailable;
    expect(d.detect(640, 480, 640), expected);
    expect(d.recordCount, 0);
    expect(d.timings.length, 3);
    d.dispose();
  }, skip: skip);

  test('record encoding through the binding matches the shared golden', () {
    final lib = MarkerDetectorLibrary.open(path);
    final d = MarkerDetector.create(lib, 0)!;
    // Inputs in detector scratch: corners (8 f32), rvec, tvec (3 f64 each),
    // output record (64 B).
    final scratch = d.imageBuffer(256);
    expect(lib.scratch(nullptr, 0), nullptr,
        reason: 'a null detector has no scratch');
    final golden = File('test_assets/visibility_grid/ingest_markers.bin')
        .readAsBytesSync();
    final pointer = d.imagePointer;
    final corners = pointer.cast<Float>();
    final rvec = Pointer<Double>.fromAddress(pointer.address + 32);
    final tvec = Pointer<Double>.fromAddress(pointer.address + 56);
    final out = Pointer<Uint8>.fromAddress(pointer.address + 128);
    for (var i = 0; i < 2; i++) {
      final c = <double>[
        100.0 + i,
        100,
        140.0 + i,
        100,
        140.0 + i,
        140,
        100.0 + i,
        140
      ];
      for (var k = 0; k < 8; k++) {
        corners[k] = c[k];
      }
      rvec[0] = 0.1;
      rvec[1] = -0.2;
      rvec[2] = 0.3;
      tvec[0] = 0.05 * i;
      tvec[1] = 0;
      tvec[2] = 1.5;
      lib.encodeRecord(
        12 + i,
        MarkerDictionary.arucoMip36h12,
        i == 0 ? 0 : MarkerDetectionLayout.flagRejectedBlur,
        corners,
        rvec,
        tvec,
        0.75,
        30,
        200 / 255,
        out,
      );
      final record = scratch.sublist(128, 192);
      final at = IngestHeaderLayout.bytes + 8 + 64 * i;
      expect(record, orderedEquals(golden.sublist(at, at + 64)),
          reason: 'record $i');
    }
    // Diagonal centre and viewing angle.
    final square = <double>[10, 10, 30, 10, 30, 30, 10, 30];
    for (var k = 0; k < 8; k++) {
      corners[k] = square[k];
    }
    final centre = Pointer<Float>.fromAddress(pointer.address + 64);
    expect(lib.diagonalCentre(corners, centre), 0);
    expect(centre[0], 20);
    expect(centre[1], 20);
    rvec[0] = -math.pi * math.cos(math.pi / 6);
    rvec[1] = 0;
    rvec[2] = math.pi * math.sin(math.pi / 6);
    tvec[0] = 0;
    tvec[1] = 0;
    tvec[2] = 1;
    expect(lib.viewingAngleDeg(rvec, tvec), closeTo(60, 1e-3));
    d.dispose();
  }, skip: skip);
}
