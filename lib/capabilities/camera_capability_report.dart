import '../models/camera_resolution.dart';

/// Device metadata, separate from real capture and control observations.
///
/// A missing flag means unknown. A hardware format or encoder must never be
/// offered as a capture mode unless [pipelineFormats] includes that format.
class CameraCapabilityReport {
  const CameraCapabilityReport({
    required this.schemaVersion,
    required this.deviceModel,
    required this.platform,
    required this.osVersion,
    required this.cameraId,
    required this.hardwareFormats,
    required this.pipelineFormats,
    required this.encoderFormats,
    required this.resolutionsByFormat,
    required this.pipelineResolutionsByFormat,
    required this.depthSupport,
    required this.controls,
    required this.hdrSupport,
  });

  const CameraCapabilityReport.unknown()
      : schemaVersion = 0,
        deviceModel = '',
        platform = '',
        osVersion = '',
        cameraId = '',
        hardwareFormats = const {},
        pipelineFormats = const [],
        encoderFormats = const [],
        resolutionsByFormat = const {},
        pipelineResolutionsByFormat = const {},
        depthSupport = const {},
        controls = const {},
        hdrSupport = const {};

  factory CameraCapabilityReport.fromMap(Map<String, dynamic> map) {
    Map<String, bool?> flags(Object? value) => value is Map
        ? Map<String, bool?>.unmodifiable({
            for (final entry in value.entries)
              if (entry.key is String)
                entry.key as String:
                    entry.value is bool ? entry.value as bool : null,
          })
        : const {};
    List<String> strings(Object? value) => value is List
        ? List<String>.unmodifiable(value.whereType<String>())
        : const [];
    Map<String, List<CameraResolution>> sizes(Object? value) => value is Map
        ? Map<String, List<CameraResolution>>.unmodifiable({
            for (final entry in value.entries)
              if (entry.key is String && entry.value is List)
                entry.key as String: List<CameraResolution>.unmodifiable(
                  (entry.value as List)
                      .whereType<Map>()
                      .where((row) =>
                          row['width'] is int &&
                          row['height'] is int &&
                          (row['width'] as int) > 0 &&
                          (row['height'] as int) > 0)
                      .map<CameraResolution>((row) => CameraResolution.fromMap(
                            Map<String, dynamic>.from(row),
                          ))
                      .toSet(),
                ),
          })
        : const {};
    String text(String key) => map[key] is String ? map[key] as String : '';
    return CameraCapabilityReport(
      schemaVersion:
          map['schemaVersion'] is int ? map['schemaVersion'] as int : 0,
      deviceModel: text('deviceModel'),
      platform: text('platform'),
      osVersion: text('osVersion'),
      cameraId: text('cameraId'),
      hardwareFormats: flags(map['hardwareFormats']),
      pipelineFormats: strings(map['pipelineFormats']),
      encoderFormats: strings(map['encoderFormats']),
      resolutionsByFormat: sizes(map['resolutionsByFormat']),
      pipelineResolutionsByFormat: sizes(map['pipelineResolutionsByFormat']),
      depthSupport: flags(map['depthSupport']),
      controls: flags(map['controls']),
      hdrSupport: flags(map['hdrSupport']),
    );
  }

  /// Serializable report for a device-scoped cache or curated device entry.
  /// Unknown flags remain explicit nulls; metadata never becomes test evidence.
  Map<String, dynamic> toMap() {
    Map<String, List<Map<String, dynamic>>> sizes(
      Map<String, List<CameraResolution>> values,
    ) =>
        {
          for (final entry in values.entries)
            entry.key: entry.value.map((size) => size.toMap()).toList(),
        };
    return {
      'schemaVersion': schemaVersion,
      'evidence': evidence,
      'deviceModel': deviceModel,
      'platform': platform,
      'osVersion': osVersion,
      'cameraId': cameraId,
      'hardwareFormats': Map<String, bool?>.of(hardwareFormats),
      'pipelineFormats': List<String>.of(pipelineFormats),
      'encoderFormats': List<String>.of(encoderFormats),
      'resolutionsByFormat': sizes(resolutionsByFormat),
      'pipelineResolutionsByFormat': sizes(pipelineResolutionsByFormat),
      'depthSupport': Map<String, bool?>.of(depthSupport),
      'controls': Map<String, bool?>.of(controls),
      'hdrSupport': Map<String, bool?>.of(hdrSupport),
    };
  }

  final int schemaVersion;
  final String deviceModel;
  final String platform;
  final String osVersion;
  final String cameraId;
  final Map<String, bool?> hardwareFormats;
  final List<String> pipelineFormats;
  final List<String> encoderFormats;
  final Map<String, List<CameraResolution>> resolutionsByFormat;

  /// Active-route geometry metadata, not proof each size can capture.
  final Map<String, List<CameraResolution>> pipelineResolutionsByFormat;
  final Map<String, bool?> depthSupport;
  final Map<String, bool?> controls;
  final Map<String, bool?> hdrSupport;
  String get evidence => 'advertised';
}
