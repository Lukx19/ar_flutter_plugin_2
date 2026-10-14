import 'package:flutter/foundation.dart';

/// How the native view continues after an ARCore playback dataset ends.
enum ARPlaybackRestart {
  /// Rebuild the AR view, so a new ARCore session replays the dataset.
  view('view'),

  /// Pause the same session, set the dataset again and resume.
  session('session'),

  /// Leave the session at the end of the dataset.
  off('off');

  const ARPlaybackRestart(this.wireValue);

  final String wireValue;

  /// Unknown values select the default [view].
  static ARPlaybackRestart parse(String value) {
    for (final restart in values) {
      if (restart.wireValue == value.trim()) return restart;
    }
    return view;
  }
}

/// Debug/profile test mode: the AR session replays a recording instead of
/// the camera: an ARCore Recording & Playback dataset on Android, a recorded
/// ARKit session folder on the iOS capture view.
///
/// [datasetPath] is an absolute device path or a relative path: on Android
/// relative to the app's external files directory (`recordings/walk1.mp4`),
/// on iOS a folder under the app's `Library/Application Support/recordings/`
/// (`room2-iphone12pro-20261014`; a leading `recordings/` is accepted). [restartGeneration]
/// counts view restarts; it is part of the creation parameters so a new
/// generation creates a new platform view and ARCore session.
@immutable
class ARPlaybackConfig {
  const ARPlaybackConfig({
    required this.datasetPath,
    this.restart = ARPlaybackRestart.view,
    this.restartGeneration = 0,
    this.trackIds = const <String>[],
  });

  final String datasetPath;
  final ARPlaybackRestart restart;
  final int restartGeneration;

  /// Custom data tracks (UUID strings) the view reads from every playback
  /// frame and reports with `ARSessionManager.onArPlaybackTrackData`.
  final List<String> trackIds;

  /// The configuration a build may use: null in release builds and for an
  /// empty path, so release apps never replay a dataset.
  static ARPlaybackConfig? forBuild({
    required String datasetPath,
    String restart = 'view',
    int restartGeneration = 0,
    List<String> trackIds = const <String>[],
    bool isReleaseBuild = kReleaseMode,
  }) {
    final path = datasetPath.trim();
    if (isReleaseBuild || path.isEmpty) return null;
    return ARPlaybackConfig(
      datasetPath: path,
      restart: ARPlaybackRestart.parse(restart),
      restartGeneration: restartGeneration,
      trackIds: trackIds,
    );
  }

  ARPlaybackConfig withRestartGeneration(int generation) => ARPlaybackConfig(
    datasetPath: datasetPath,
    restart: restart,
    restartGeneration: generation,
    trackIds: trackIds,
  );

  ARPlaybackConfig withTrackIds(List<String> ids) => ARPlaybackConfig(
    datasetPath: datasetPath,
    restart: restart,
    restartGeneration: restartGeneration,
    trackIds: List<String>.unmodifiable(ids),
  );

  /// Creation parameters of the Android AR view and the iOS capture view;
  /// merged into the view parameters (the iOS view reads the same keys).
  Map<String, Object> toCreationParams() => <String, Object>{
    'arcorePlaybackDataset': datasetPath,
    'arcorePlaybackRestart': restart.wireValue,
    'arcorePlaybackRestartGeneration': restartGeneration,
    if (trackIds.isNotEmpty) 'arcorePlaybackTrackIds': trackIds,
  };

  @override
  bool operator ==(Object other) =>
      other is ARPlaybackConfig &&
      other.datasetPath == datasetPath &&
      other.restart == restart &&
      other.restartGeneration == restartGeneration &&
      listEquals(other.trackIds, trackIds);

  @override
  int get hashCode => Object.hash(
    datasetPath,
    restart,
    restartGeneration,
    Object.hashAll(trackIds),
  );

  @override
  String toString() =>
      'ARPlaybackConfig($datasetPath, ${restart.wireValue}, $restartGeneration)';
}
