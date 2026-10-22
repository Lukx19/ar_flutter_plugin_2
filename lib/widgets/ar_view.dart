import 'package:ar_flutter_plugin_2/managers/ar_anchor_manager.dart';
import 'package:ar_flutter_plugin_2/managers/ar_location_manager.dart';
import 'package:ar_flutter_plugin_2/models/ar_capture_config.dart';
import 'package:ar_flutter_plugin_2/models/ar_capture_view_config.dart';
import 'package:ar_flutter_plugin_2/models/ar_playback_config.dart';
import 'package:ar_flutter_plugin_2/models/ar_session_inputs.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:ar_flutter_plugin_2/managers/ar_session_manager.dart';
import 'package:ar_flutter_plugin_2/managers/ar_object_manager.dart';
import 'package:ar_flutter_plugin_2/datatypes/config_planedetection.dart';

// Type definitions to enforce a consistent use of the API
typedef ARViewCreatedCallback = void Function(
    ARSessionManager arSessionManager,
    ARObjectManager arObjectManager,
    ARAnchorManager arAnchorManager,
    ARLocationManager arLocationManager);

/// Factory method for creating a platform-dependent AR view
abstract class PlatformARView {
  factory PlatformARView(TargetPlatform platform) {
    switch (platform) {
      case TargetPlatform.android:
        return AndroidARView();
      case TargetPlatform.iOS:
        return IosARView();
      default:
        throw FlutterError;
    }
  }

  Widget build(
      {@required BuildContext context,
      @required ARViewCreatedCallback arViewCreatedCallback,
      @required PlaneDetectionConfig planeDetectionConfig,
      ARCaptureConfig? captureConfig,
      String? rearCameraId,
      ARPlaybackConfig? playback,
      ARCaptureViewConfig? captureView,
      ARSessionInputs sessionInputs = const ARSessionInputs()});

  /// Callback function that is executed once the view is established
  void onPlatformViewCreated(int id);
}

/// Instantiates [ARSessionManager], [ARObjectManager] and returns them to the widget instantiating the [ARView] using the [arViewCreatedCallback]
createManagers(
    int id,
    BuildContext? context,
    ARViewCreatedCallback? arViewCreatedCallback,
    PlaneDetectionConfig? planeDetectionConfig,
    ARCaptureConfig? captureConfig) {
  if (context == null ||
      arViewCreatedCallback == null ||
      planeDetectionConfig == null) {
    return;
  }
  arViewCreatedCallback(
      ARSessionManager(id, context, planeDetectionConfig,
          captureConfig: captureConfig),
      ARObjectManager(id),
      ARAnchorManager(id),
      ARLocationManager());
}

/// Android-specific implementation of [PlatformARView]
/// Uses the texture-based surface-controller platform view route.
/// Native SceneView rendering is independent of this outer composition choice.
class AndroidARView implements PlatformARView {
  late BuildContext? _context;
  late ARViewCreatedCallback? _arViewCreatedCallback;
  late PlaneDetectionConfig? _planeDetectionConfig;
  late ARCaptureConfig? _captureConfig;

  @override
  void onPlatformViewCreated(int id) {
    print("Android platform view created!");
    createManagers(id, _context, _arViewCreatedCallback, _planeDetectionConfig,
        _captureConfig);
  }

  @override
  Widget build(
      {BuildContext? context,
      ARViewCreatedCallback? arViewCreatedCallback,
      PlaneDetectionConfig? planeDetectionConfig,
      ARCaptureConfig? captureConfig,
      String? rearCameraId,
      ARPlaybackConfig? playback,
      ARCaptureViewConfig? captureView,
      ARSessionInputs sessionInputs = const ARSessionInputs()}) {
    // [captureView] is the iOS capture view; Android ignores it.
    _context = context;
    _arViewCreatedCallback = arViewCreatedCallback;
    _planeDetectionConfig = planeDetectionConfig;
    _captureConfig = captureConfig;
    // This is used in the platform side to register the view.
    final String viewType = 'ar_flutter_plugin_2';
    // Pass parameters to the platform side.
    final Map<String, dynamic> creationParams = <String, dynamic>{
      // ARCore session features are immutable after session creation. Keep this
      // creation-time value in sync with the Android view key below so a mode
      // change disposes the entire platform view and starts a new AR session.
      'enableHighResCapture': captureConfig?.enableHighResCapture ?? false,
      'retainAssessedOriginals': captureConfig?.retainAssessedOriginals ?? false,
      'rearCameraId': rearCameraId,
      // Depth mode and camera config are fixed when the session starts.
      ...sessionInputs.toCreationParams(),
      // Debug/profile playback test mode; absent in normal and release builds.
      ...?playback?.toCreationParams(),
    };

    return PlatformViewLink(
      key: ValueKey<(bool, String?, bool, ARPlaybackConfig?, ARSessionInputs)>(
        (
          captureConfig?.enableHighResCapture ?? false,
          rearCameraId,
          captureConfig?.retainAssessedOriginals ?? false,
          playback,
          sessionInputs,
        ),
      ),
      viewType: viewType,
      surfaceFactory: (context, controller) => AndroidViewSurface(
        controller: controller as AndroidViewController,
        hitTestBehavior: PlatformViewHitTestBehavior.opaque,
        gestureRecognizers: const {},
      ),
      onCreatePlatformView: (params) {
        final AndroidViewController controller =
            PlatformViewsService.initSurfaceAndroidView(
          id: params.id,
          viewType: viewType,
          layoutDirection: TextDirection.ltr,
          creationParams: creationParams,
          creationParamsCodec: const StandardMessageCodec(),
        );
        controller
            .addOnPlatformViewCreatedListener(params.onPlatformViewCreated);
        controller.addOnPlatformViewCreatedListener(onPlatformViewCreated);
        controller.create();
        return controller;
      },
    );
  }
}

/// iOS-specific implementation of [PlatformARView]
class IosARView implements PlatformARView {
  BuildContext? _context;
  ARViewCreatedCallback? _arViewCreatedCallback;
  PlaneDetectionConfig? _planeDetectionConfig;
  ARCaptureConfig? _captureConfig;

  @override
  void onPlatformViewCreated(int id) {
    print("iOS platform view created!");
    createManagers(id, _context, _arViewCreatedCallback, _planeDetectionConfig,
        _captureConfig);
  }

  @override
  Widget build(
      {BuildContext? context,
      ARViewCreatedCallback? arViewCreatedCallback,
      PlaneDetectionConfig? planeDetectionConfig,
      ARCaptureConfig? captureConfig,
      String? rearCameraId,
      ARPlaybackConfig? playback,
      ARCaptureViewConfig? captureView,
      ARSessionInputs sessionInputs = const ARSessionInputs()}) {
    _context = context;
    _arViewCreatedCallback = arViewCreatedCallback;
    _planeDetectionConfig = planeDetectionConfig;
    // Assessed-original production is unsupported on iOS. Reader capability is
    // independent; do not advertise a producer mode this platform cannot honor.
    _captureConfig = captureConfig?.copyWith(retainAssessedOriginals: false);
    // The capture route runs the ARKit capture view on a Metal host; every
    // other route keeps the legacy view.
    final String viewType = iosViewType(captureView);
    // Debug/profile playback: the capture view replays a recorded ARKit
    // session (a folder under Application Support/recordings/); the legacy
    // view ignores [playback].
    final ARPlaybackConfig? viewPlayback =
        captureView == null ? null : playback;
    final Map<String, dynamic> creationParams = <String, dynamic>{
      ...?captureView?.toCreationParams(),
      ...?viewPlayback?.toCreationParams(),
    };

    return UiKitView(
      key: ValueKey<(ARCaptureViewConfig?, ARPlaybackConfig?)>(
          (captureView, viewPlayback)),
      viewType: viewType,
      layoutDirection: TextDirection.ltr,
      creationParams: creationParams,
      creationParamsCodec: const StandardMessageCodec(),
      onPlatformViewCreated: onPlatformViewCreated,
    );
  }
}

/// Platform view type of the iOS AR view: the capture view when
/// [captureView] is set, the legacy view otherwise.
String iosViewType(ARCaptureViewConfig? captureView) =>
    captureView == null ? legacyViewType : ARCaptureViewConfig.viewType;

/// Platform view type of the legacy AR view (both platforms).
const String legacyViewType = 'ar_flutter_plugin_2';

/// If camera permission is granted, [ARView] creates a platform-dependent view from the factory method [PlatformARView]. To instantiate an [ARView],
/// the calling widget needs to pass the callback function [onARViewCreated] to which the function [createManagers] returns managers such as the
/// [ARSessionManager] and the [ARObjectManager]. [planeDetectionConfig] is passed to the constructor to determine which types of planes the underlying
/// AR frameworks should track (defaults to none).
/// If camera permission is not given, the user is prompted to grant it. To modify the UI of the prompts, the following named parameters can be used:
/// [permissionPromptDescription], [permissionPromptButtonText] and [permissionPromptParentalRestriction].
class ARView extends StatefulWidget {
  final String permissionPromptDescription;
  final String permissionPromptButtonText;
  final String permissionPromptParentalRestriction;

  /// Function to be called when the AR View is created
  final ARViewCreatedCallback onARViewCreated;

  /// Configures the type of planes ARCore and ARKit should track. defaults to none
  final PlaneDetectionConfig planeDetectionConfig;

  /// Optional capture configuration for direct configuration pattern
  final ARCaptureConfig? captureConfig;

  /// An ARCore-advertised rear Camera2 ID to use when creating the session.
  /// Changes rebuild the native platform view; an active AR session cannot
  /// safely hot-swap cameras.
  final String? rearCameraId;

  /// Debug/profile playback test mode: an ARCore Recording & Playback
  /// dataset on Android, a recorded ARKit session on the iOS capture view
  /// (the legacy iOS view ignores it). Null in release builds; changes
  /// rebuild the native platform view.
  final ARPlaybackConfig? playback;

  /// iOS capture view configuration; set only by the capture route. Null
  /// keeps the legacy iOS view. Android ignores it.
  final ARCaptureViewConfig? captureView;

  /// Depth input and camera image tier of the session (Android). On iOS
  /// the app maps them into [captureView]. Changes rebuild the native view.
  final ARSessionInputs sessionInputs;

  /// Configures whether or not to display the device's platform type above the AR view. Defaults to false
  final bool showPlatformType;

  ARView(
      {Key? key,
      required this.onARViewCreated,
      this.planeDetectionConfig = PlaneDetectionConfig.none,
      this.captureConfig,
      this.rearCameraId,
      this.playback,
      this.captureView,
      this.sessionInputs = const ARSessionInputs(),
      this.showPlatformType = false,
      this.permissionPromptDescription =
          "Camera permission must be given to the app for AR functions to work",
      this.permissionPromptButtonText = "Grant Permission",
      this.permissionPromptParentalRestriction =
          "Camera permission is restriced by the OS, please check parental control settings"})
      : super(key: key);
  @override
  _ARViewState createState() => _ARViewState(
      showPlatformType: this.showPlatformType,
      permissionPromptDescription: this.permissionPromptDescription,
      permissionPromptButtonText: this.permissionPromptButtonText,
      permissionPromptParentalRestriction:
          this.permissionPromptParentalRestriction);
}

class _ARViewState extends State<ARView> {
  PermissionStatus _cameraPermission = PermissionStatus.denied;
  bool showPlatformType;
  String permissionPromptDescription;
  String permissionPromptButtonText;
  String permissionPromptParentalRestriction;

  _ARViewState(
      {required this.showPlatformType,
      required this.permissionPromptDescription,
      required this.permissionPromptButtonText,
      required this.permissionPromptParentalRestriction});

  /// Session of the platform view this widget created last; told when the
  /// view goes away ([ARSessionManager.platformViewDetached]).
  ARSessionManager? _createdSession;

  /// Platform the last view was built for.
  TargetPlatform? _createdPlatform;

  /// Releases the Dart side of a session whose platform view is gone, on iOS
  /// only: `CaptureARView.deinit` sends nothing after the view goes, so the
  /// Dart handlers can close. An Android view still drains its capture
  /// teardown after removal and may send `onCaptureFinalized` or
  /// `onNativeCaptureV2Event`; its session keeps the inbound handler and
  /// streams until the app disposes it or a replacement view takes over.
  void _detachSession(ARSessionManager session) {
    if (_createdPlatform != TargetPlatform.iOS) return;
    session.platformViewDetached();
  }

  @override
  void initState() {
    super.initState();
    initCameraPermission();
  }

  @override
  void dispose() {
    // The platform view goes with this widget: a session the app did not
    // dispose first must not keep calling its gone channels.
    final created = _createdSession;
    if (created != null) _detachSession(created);
    _createdSession = null;
    super.dispose();
  }

  void _onARViewCreated(
      ARSessionManager session,
      ARObjectManager objects,
      ARAnchorManager anchors,
      ARLocationManager locations) {
    // A new platform view replaced the previous one, which is gone.
    final previous = _createdSession;
    if (previous != null && !identical(previous, session)) {
      _detachSession(previous);
    }
    _createdSession = session;
    _createdPlatform = Theme.of(context).platform;
    widget.onARViewCreated(session, objects, anchors, locations);
  }

  initCameraPermission() async {
    requestCameraPermission();
  }

  requestCameraPermission() async {
    final cameraPermission = await Permission.camera.request();
    setState(() {
      _cameraPermission = cameraPermission;
    });
  }

  requestCameraPermissionFromSettings() async {
    final cameraPermission = await Permission.camera.request();
    if (cameraPermission == PermissionStatus.permanentlyDenied) {
      openAppSettings();
    }
    setState(() {
      _cameraPermission = cameraPermission;
    });
  }

  @override
  build(BuildContext context) {
    switch (_cameraPermission) {
      case (PermissionStatus
            .limited): //iOS-specific: permissions granted for this specific application
      case (PermissionStatus.granted):
        {
          return Column(children: [
            if (showPlatformType) Text(Theme.of(context).platform.toString()),
            Expanded(
                child: PlatformARView(Theme.of(context).platform).build(
                    context: context,
                    arViewCreatedCallback: _onARViewCreated,
                    planeDetectionConfig: widget.planeDetectionConfig,
                    captureConfig: widget.captureConfig,
                    rearCameraId: widget.rearCameraId,
                    playback: widget.playback,
                    captureView: widget.captureView,
                    sessionInputs: widget.sessionInputs)),
          ]);
        }
      case (PermissionStatus.denied):
        {
          return Center(
              child: Column(
            children: [
              Text(permissionPromptDescription),
              ElevatedButton(
                  child: Text(permissionPromptButtonText),
                  onPressed: () async => {await requestCameraPermission()})
            ],
          ));
        }
      case (PermissionStatus
            .permanentlyDenied): //Android-specific: User needs to open Settings to give permissions
        {
          return Center(
              child: Column(
            children: [
              Text(permissionPromptDescription),
              ElevatedButton(
                  child: Text(permissionPromptButtonText),
                  onPressed: () async =>
                      {await requestCameraPermissionFromSettings()})
            ],
          ));
        }
      case (PermissionStatus.restricted):
        {
          //iOS only
          return Center(child: Text(permissionPromptParentalRestriction));
        }
      default:
        return Text('something went wrong');
    }
  }
}
