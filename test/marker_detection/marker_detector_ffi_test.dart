// Runs against a host build of the marker detector library (geometry only,
// no detection backend):
//
//   clang++ -std=c++17 -O2 -shared \
//     -Inative/marker_detector/include -Inative/marker_detector/src \
//     native/marker_detector/src/marker_geometry.cpp \
//     native/marker_detector/src/marker_pose.cpp \
//     native/marker_detector/src/marker_detector.cpp \
//     -o <dir>/capture3d_marker_detector.dll
//   CAPTURE3D_MARKER_DETECTOR_LIBRARY=<dir>/capture3d_marker_detector.dll \
//     flutter test test/marker_detection/marker_detector_ffi_test.dart
//
// The AprilTag build (the Android and iOS backend) also decodes the committed golden
// image; build it with the AprilTag sources and defines:
//
//   clang -O2 -c -w -Inative/marker_detector/third_party/apriltag \
//     -Inative/marker_detector/src <third_party/apriltag/*.c, common/*.c,
//     src/aruco_mip_36h12_family.c>
//   clang++ -std=c++17 -O2 -shared -DC3MD_WITH_APRILTAG=1 <the include and
//     src flags above, -Inative/marker_detector/third_party/apriltag>
//     src/marker_geometry.cpp src/marker_pose.cpp src/marker_apriltag.cpp
//     src/marker_detector.cpp <the C objects> -o <dir>/libmarker.dylib
//
// Without the variable the tests are skipped.
import 'dart:convert';
import 'dart:ffi';
import 'dart:io';
import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

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

  // AprilTag build (the Android and iOS backend) only: a library without a
  // detection backend reports unavailable and the test returns early.
  test('detection build decodes the committed golden image', () async {
    final lib = MarkerDetectorLibrary.open(path);
    final d =
        MarkerDetector.create(lib, MarkerDetectorDictionary.arucoMip36h12)!;
    if (!d.available) {
      d.dispose();
      return;
    }
    expect(MarkerDetector.create(lib, MarkerDetectorDictionary.aruco4x4_50),
        isNull,
        reason: 'AprilTag decodes no 4X4 dictionary');
    final goldens = jsonDecode(
            File('test_assets/marker_detection/marker_goldens.json')
                .readAsStringSync()) as Map<String, dynamic>;
    final camera = goldens['camera'] as Map<String, dynamic>;
    final image = (goldens['images'] as List).first as Map<String, dynamic>;
    final png = File('test_assets/marker_detection/${image['file']}')
        .readAsBytesSync();
    final rgba = await decodeRgba(png);
    final width = camera['width'] as int, height = camera['height'] as int;
    final grey = d.imageBuffer(width * height);
    for (var i = 0; i < width * height; i++) {
      grey[i] = rgba[4 * i];
    }
    d.setCamera((camera['fx'] as num).toDouble(),
        (camera['fy'] as num).toDouble(), (camera['cx'] as num).toDouble(),
        (camera['cy'] as num).toDouble());
    d.setMarkerSize((image['markerSizeMetres'] as num).toDouble());
    final markers = image['markers'] as List;
    expect(d.detect(width, height, width), markers.length);
    expect(d.estimatePose(), markers.length);
    for (var i = 0; i < markers.length; i++) {
      final m = markers[i] as Map<String, dynamic>;
      expect(d.records.getUint16(64 * i, Endian.little), m['id']);
      final truth = (m['truth'] as List).cast<num>();
      for (var k = 0; k < 8; k++) {
        expect(d.records.getFloat32(64 * i + 4 + 4 * k, Endian.little),
            closeTo(truth[k].toDouble(), 0.5));
      }
    }
    d.dispose();
  }, skip: skip);
}

/// RGBA bytes of an encoded image (grey PNG: R = G = B = grey).
Future<Uint8List> decodeRgba(Uint8List encoded) async {
  final codec = await ui.instantiateImageCodec(encoded);
  final frame = await codec.getNextFrame();
  final data =
      await frame.image.toByteData(format: ui.ImageByteFormat.rawRgba);
  return data!.buffer.asUint8List();
}
