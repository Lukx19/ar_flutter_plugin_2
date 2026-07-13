import 'package:ar_flutter_plugin_2/models/ar_anchor.dart';
import 'package:flutter/services.dart';
import 'package:flutter/material.dart';

/// ARCore's estimate of whether the currently observed visual features are
/// sufficient to host a durable Google Cloud Anchor.
enum AndroidFeatureMapQuality { insufficient, sufficient, good }

// Type definitions to enforce a consistent use of the API
typedef AnchorUploadedHandler = void Function(ARAnchor arAnchor);
typedef AnchorDownloadedHandler = ARAnchor Function(
    Map<String, dynamic> serializedAnchor);

/// Handles all anchor-related functionality of an [ARView], including configuration and usage of collaborative sessions
class ARAnchorManager {
  /// Platform channel used for communication from and to [ARAnchorManager]
  late MethodChannel _channel;

  /// Debugging status flag. If true, all platform calls are printed. Defaults to false.
  final bool debug;

  /// Reference to all anchors that are being uploaded to the google cloud anchor API
  List<ARAnchor> pendingAnchors = [];

  /// Callback that is triggered once an anchor has successfully been uploaded to the google cloud anchor API
  AnchorUploadedHandler? onAnchorUploaded;

  /// Callback that is triggered once an anchor has successfully been downloaded from the google cloud anchor API and resolved within the current scene
  AnchorDownloadedHandler? onAnchorDownloaded;

  ARAnchorManager(int id, {this.debug = false}) {
    _channel = MethodChannel('aranchors_$id');
    _channel.setMethodCallHandler(_platformCallHandler);
    if (debug) {
      print("ARAnchorManager initialized");
    }
  }

  /// Activates collaborative AR mode (using Google Cloud Anchors)
  Future<void> initGoogleCloudAnchorMode() async {
    await _channel.invokeMethod<void>('initGoogleCloudAnchorMode', {});
  }

  /// Estimates hosting quality at ARCore's current native camera pose.
  ///
  /// App-facing pose streams may use an export coordinate convention, so the
  /// native camera pose is deliberately selected on the platform side.
  Future<AndroidFeatureMapQuality> estimateFeatureMapQualityForHosting() async {
    final quality = await _channel.invokeMethod<String>(
      'estimateFeatureMapQualityForHosting',
    );
    return switch (quality) {
      'insufficient' => AndroidFeatureMapQuality.insufficient,
      'sufficient' => AndroidFeatureMapQuality.sufficient,
      'good' => AndroidFeatureMapQuality.good,
      _ => throw PlatformException(
          code: 'ANCHOR_QUALITY_INVALID',
          message: 'ARCore returned an unknown feature-map quality: $quality',
        ),
    };
  }

  /// Creates a local anchor at ARCore's current native camera pose.
  Future<ARPlaneAnchor> addAnchorAtCurrentCameraPose({
    required String name,
    int ttl = 1,
  }) async {
    if (ttl < 1 || ttl > 365) {
      throw ArgumentError.value(ttl, 'ttl', 'must be between 1 and 365 days');
    }
    final serialized = await _channel.invokeMapMethod<String, dynamic>(
      'addAnchorAtCurrentCameraPose',
      <String, dynamic>{'name': name, 'ttl': ttl},
    );
    if (serialized == null) {
      throw PlatformException(
        code: 'ANCHOR_CREATE_FAILED',
        message: 'ARCore did not return the created local anchor.',
      );
    }
    return ARPlaneAnchor.fromJson(serialized);
  }

  Future<dynamic> _platformCallHandler(MethodCall call) async {
    if (debug) {
      print('_platformCallHandler call ${call.method} ${call.arguments}');
    }
    try {
      switch (call.method) {
        case 'onError':
          print(call.arguments);
          break;
        case 'onCloudAnchorUploaded':
          final name = call.arguments["name"];
          final cloudanchorid = call.arguments["cloudanchorid"];
          print(
              "UPLOADED ANCHOR WITH ID: " + cloudanchorid + ", NAME: " + name);
          final currentAnchor =
              pendingAnchors.where((element) => element.name == name).first;
          // Update anchor with cloud anchor ID
          (currentAnchor as ARPlaneAnchor).cloudanchorid = cloudanchorid;
          // Remove anchor from list of pending anchors
          pendingAnchors.remove(currentAnchor);
          // Notify callback
          if (onAnchorUploaded != null) {
            onAnchorUploaded!(currentAnchor);
          }
          break;
        case "onAnchorDownloadSuccess":
          final serializedAnchor = call.arguments;
          if (onAnchorDownloaded != null) {
            ARAnchor anchor = onAnchorDownloaded!(
                Map<String, dynamic>.from(serializedAnchor));
            return anchor.name;
          } else {
            return serializedAnchor["name"];
          }
        default:
          if (debug) {
            print('Unimplemented method ${call.method} ');
          }
      }
    } catch (e) {
      print('Error caught: ' + e.toString());
    }
    return Future.value();
  }

  /// Add given anchor to the underlying AR scene
  Future<bool?> addAnchor(ARAnchor anchor) async {
    try {
      return await _channel.invokeMethod<bool>('addAnchor', anchor.toJson());
    } on PlatformException catch (e) {
      return false;
    }
  }

  /// Remove given anchor and all its children from the AR Scene
  removeAnchor(ARAnchor anchor) {
    _channel.invokeMethod<String>('removeAnchor', {'name': anchor.name});
  }

  /// Upload given anchor from the underlying AR scene to the Google Cloud Anchor API
  Future<bool?> uploadAnchor(ARAnchor anchor) async {
    if (anchor is ARPlaneAnchor &&
        (anchor.ttl == null || anchor.ttl! < 1 || anchor.ttl! > 365)) {
      throw ArgumentError.value(
        anchor.ttl,
        'anchor.ttl',
        'Cloud Anchor TTL must be between 1 and 365 days.',
      );
    }
    if (!pendingAnchors.contains(anchor)) {
      pendingAnchors.add(anchor);
    }
    try {
      final response =
          await _channel.invokeMethod<bool>('uploadAnchor', anchor.toJson());
      if (response != true) {
        pendingAnchors.remove(anchor);
      }
      return response;
    } on PlatformException {
      pendingAnchors.remove(anchor);
      rethrow;
    }
  }

  /// Try to download anchor with the given ID from the Google Cloud Anchor API and add it to the scene
  Future<bool?> downloadAnchor(String cloudanchorid) async {
    print("TRYING TO DOWNLOAD ANCHOR WITH ID " + cloudanchorid);
    return _channel.invokeMethod<bool>(
      'downloadAnchor',
      {"cloudanchorid": cloudanchorid},
    );
  }
}
