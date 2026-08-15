export 'package:ar_flutter_plugin_2/widgets/ar_view.dart';

// Core Managers
export 'package:ar_flutter_plugin_2/managers/ar_session_manager.dart';
export 'package:ar_flutter_plugin_2/managers/ar_capture_manager.dart';
export 'package:ar_flutter_plugin_2/managers/ar_anchor_manager.dart';
export 'package:ar_flutter_plugin_2/managers/ar_object_manager.dart';
export 'package:ar_flutter_plugin_2/managers/ar_point_cloud_manager.dart';
export 'package:ar_flutter_plugin_2/managers/ar_visibility_grid_manager.dart';
export 'package:ar_flutter_plugin_2/managers/ar_visibility_surface_stream.dart';
export 'package:ar_flutter_plugin_2/managers/ar_location_manager.dart';

// Camera Capabilities API
export 'package:ar_flutter_plugin_2/capabilities/ar_camera_capabilities.dart';

// Models
export 'package:ar_flutter_plugin_2/models/ar_capture_config.dart';
export 'package:ar_flutter_plugin_2/models/ar_capture_result.dart';
export 'package:ar_flutter_plugin_2/models/ar_frame_pose.dart';
export 'package:ar_flutter_plugin_2/models/ar_camera_intrinsics.dart';
export 'package:ar_flutter_plugin_2/models/ar_point_cloud.dart';
export 'package:ar_flutter_plugin_2/models/ar_visibility_grid.dart';
export 'package:ar_flutter_plugin_2/models/camera_resolution.dart';
export 'package:ar_flutter_plugin_2/models/focal_length.dart';
export 'package:ar_flutter_plugin_2/models/image_size.dart';
export 'package:ar_flutter_plugin_2/models/principal_point.dart';
export 'package:ar_flutter_plugin_2/models/compatibility_result.dart';

// Data Types
export 'package:ar_flutter_plugin_2/datatypes/image_format.dart';
export 'package:ar_flutter_plugin_2/datatypes/config_planedetection.dart';

// Validation
export 'package:ar_flutter_plugin_2/validation/configuration_compatibility_validator.dart';

import 'dart:async';

import 'package:flutter/services.dart';

class ArFlutterPlugin {
  static const MethodChannel _channel =
      const MethodChannel('ar_flutter_plugin_2');

  /// Private constructor to prevent accidental instantiation of the Plugin using the implicit default constructor
  ArFlutterPlugin._();

  static Future<String> get platformVersion async {
    final String version = await _channel.invokeMethod('getPlatformVersion');
    return version;
  }
}
