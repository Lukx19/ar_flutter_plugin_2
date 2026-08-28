import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:ar_flutter_plugin_2/models/ar_point_cloud.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test(
      'synthetic ARCore and ARKit point clouds and depth maps share the bounded seam',
      () {
    final fixture = jsonDecode(
      File('test/fixtures/visibility_grid/visibility_grid_wire_v1.json')
          .readAsStringSync(),
    ) as Map<String, dynamic>;
    final scenario = (fixture['scenarios'] as List<dynamic>)
        .cast<Map<String, dynamic>>()
        .singleWhere(
          (value) => value['name'] == 'synthetic_arcore_arkit_sensor_frames',
        );
    expect(scenario['pointCloudWireVersion'], pointCloudWireVersion);

    final pointClouds = _map(scenario['pointClouds']);
    final arCore = _map(pointClouds['arcore']);
    final arCoreIds = (arCore['ids'] as List<dynamic>).cast<int>();
    final arCorePoints = _doubleList(arCore['points']);
    final arCoreFrame = ARPointCloudFrame(
      sequence: arCore['sequence'] as int,
      timestampNs: arCore['timestampNs'] as int,
      ids: Int32List.fromList(arCoreIds),
      points: Float32List.fromList(arCorePoints),
    );
    expect(arCoreFrame.count, arCoreIds.length);
    final minimumConfidence = (arCore['minimumConfidence'] as num).toDouble();
    final acceptedArCoreIds = <int>[];
    for (var index = 0; index < arCoreIds.length; index++) {
      if (arCorePoints[index * 4 + 3] >= minimumConfidence) {
        acceptedArCoreIds.add(arCoreIds[index]);
      }
    }
    expect(acceptedArCoreIds, arCore['expectedAcceptedIds']);

    final arKit = _map(pointClouds['arkit']);
    final arKitIds = (arKit['identifiers'] as List<dynamic>).cast<int>();
    final arKitPoints = _doubleList(arKit['points']);
    final arKitConfidence = (arKit['defaultConfidence'] as num).toDouble();
    final normalizedArKitPoints = <double>[];
    for (var index = 0; index < arKitIds.length; index++) {
      normalizedArKitPoints.addAll(<double>[
        arKitPoints[index * 3],
        arKitPoints[index * 3 + 1],
        arKitPoints[index * 3 + 2],
        arKitConfidence,
      ]);
    }
    final normalizedArKit = ARPointCloudFrame(
      sequence: arKit['sequence'] as int,
      timestampNs: arKit['timestampNs'] as int,
      ids: Int32List.fromList(arKitIds),
      points: Float32List.fromList(normalizedArKitPoints),
    );
    expect(
      normalizedArKit.ids,
      orderedEquals(
          (arKit['expectedNormalizedIds'] as List<dynamic>).cast<int>()),
    );
    expect(normalizedArKit.points.length, arKitIds.length * 4);
    expect(normalizedArKit.points[3], closeTo(1.0, 1e-6));

    final depthMaps = _map(scenario['depthMaps']);
    final arCoreDepth = _map(depthMaps['arcoreRaw']);
    expect(
      _validDepthPixels(
        values: (arCoreDepth['depthMillimeters'] as List<dynamic>).cast<int>(),
        confidence: (arCoreDepth['confidence'] as List<dynamic>).cast<int>(),
        minimumConfidence: arCoreDepth['minimumConfidence'] as int,
      ),
      arCoreDepth['expectedValidPixelCount'],
    );
    expect(arCoreDepth['unit'], 'millimeters');
    expect(arCoreDepth['orientation'], 'landscapeRight');
    expect(
      (arCoreDepth['depthMillimeters'] as List<dynamic>).length,
      (arCoreDepth['width'] as int) * (arCoreDepth['height'] as int),
    );

    final arKitDepth = _map(depthMaps['arkitScene']);
    const confidenceRank = <String, int>{'low': 0, 'medium': 1, 'high': 2};
    final arKitMinimum = confidenceRank[arKitDepth['minimumConfidence']]!;
    final arKitDepthValues =
        (arKitDepth['depthMeters'] as List<dynamic>).cast<num>();
    final arKitConfidenceValues =
        (arKitDepth['confidence'] as List<dynamic>).cast<String>();
    final acceptedArKitDepth = List<int>.generate(
      arKitDepthValues.length,
      (index) => arKitDepthValues[index] > 0 &&
              confidenceRank[arKitConfidenceValues[index]]! >= arKitMinimum
          ? 1
          : 0,
    ).fold<int>(0, (total, value) => total + value);
    expect(acceptedArKitDepth, arKitDepth['expectedValidPixelCount']);
    expect(arKitDepth['unit'], 'meters');
    expect(arKitDepth['orientation'], 'portrait');
    expect(arKitDepth['expectedOrientedDimensions'], <int>[2, 3]);
    final orientedAcceptedPixels = <List<int>>[];
    for (var index = 0; index < arKitDepthValues.length; index++) {
      if (arKitDepthValues[index] <= 0 ||
          confidenceRank[arKitConfidenceValues[index]]! < arKitMinimum) {
        continue;
      }
      final rawX = index % (arKitDepth['width'] as int);
      final rawY = index ~/ (arKitDepth['width'] as int);
      orientedAcceptedPixels.add(<int>[
        rawY,
        (arKitDepth['width'] as int) - 1 - rawX,
      ]);
    }
    expect(
      orientedAcceptedPixels,
      <List<int>>[
        <int>[0, 2],
        <int>[0, 0],
        <int>[1, 2],
        <int>[1, 1],
      ],
    );
  });
}

Map<String, dynamic> _map(Object? value) =>
    (value as Map<dynamic, dynamic>).cast<String, dynamic>();

List<double> _doubleList(Object? value) =>
    (value as List<dynamic>).map((item) => (item as num).toDouble()).toList();

int _validDepthPixels({
  required List<int> values,
  required List<int> confidence,
  required int minimumConfidence,
}) {
  if (values.length != confidence.length) {
    throw const FormatException('Synthetic depth map lengths differ.');
  }
  return List<int>.generate(
    values.length,
    (index) =>
        values[index] > 0 && confidence[index] >= minimumConfidence ? 1 : 0,
  ).fold<int>(0, (total, value) => total + value);
}
