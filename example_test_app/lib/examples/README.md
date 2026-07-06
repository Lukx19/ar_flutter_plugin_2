# Examples

This folder contains example code demonstrating various features of the AR Flutter Plugin.

## FlutterFlow Examples
<table>
<td>
<img src="https://avatars.githubusercontent.com/u/74943865?s=48&amp;v=4" width="30" height="30" style="max-width: 100%; margin-bottom: -9px;"> </img>
</td>
<td>
<b> You can find a complete example running on FlutterFlow here :</b><br>
https://app.flutterflow.io/project/a-r-flutter-lib-ipqw3k
</td>
</table>

## Camera Capabilities API Examples

The following examples demonstrate the new camera capabilities API (Android only):

### 📱 Basic Camera Capabilities (`camera_capabilities_basic.dart`)
Demonstrates core capability querying functionality:
- Query supported camera resolutions
- Query supported image formats (JPEG/RAW)
- Query ISO sensitivity and exposure time ranges  
- Test specific resolution/format support
- Platform compatibility checks

**Key Features:**
- Real-time capability querying
- Interactive resolution and format testing
- User-friendly capability display
- Error handling and platform validation

### ⚙️ Configuration Validation (`camera_config_validation.dart`)
Shows the configuration validation and recommendation system:
- Create and validate different `ARCaptureConfig` configurations
- Display validation results with errors and warnings
- Show recommended vs optimal configurations
- Performance impact assessment for each configuration

**Key Features:**
- Multiple test configuration scenarios
- Real-time validation feedback
- Performance impact visualization
- Suggested configuration recommendations

### 🔍 Camera Intrinsics Models (`camera_intrinsics_models.dart`)
Demonstrates usage of camera intrinsic data models:
- `CameraResolution`: Aspect ratio, pixel count, orientation calculations
- `FocalLength`: Symmetry checks and averaging calculations
- `PrincipalPoint`: Centering validation for different resolutions
- `ImageSize`: Memory usage calculations and consistency checks

**Key Features:**
- Interactive model property exploration
- Memory usage calculators
- Comparison and analysis tools
- Model feature demonstrations

### 🎯 AR Integration (`ar_with_camera_capabilities.dart`)
Shows integration of camera capabilities with existing AR functionality:
- Query camera capabilities before starting AR session
- Configure optimal capture settings for AR
- Real-time performance monitoring during AR session
- Adaptive configuration switching (low/high performance)
- Integration with AR session management

**Key Features:**
- Device-specific configuration selection
- Real-time AR performance monitoring
- Dynamic configuration switching
- AR session state management
- Performance impact visualization

## Platform Support

**Camera Capabilities API**: Currently supported on **Android only**
- Requires Android API level 21 (Android 5.0) or higher
- Uses Camera2 API for capability querying
- Automatic platform detection with graceful fallbacks

**AR Features**: Supported on both Android and iOS
- Standard AR functionality works on all supported platforms
- Camera capabilities enhance the experience on Android

## Usage Instructions

1. **Import the plugin:**
   ```dart
   import 'package:ar_flutter_plugin_2/ar_flutter_plugin.dart';
   ```

2. **Check platform support:**
   ```dart
   final capabilities = ARCameraCapabilities();
   if (capabilities.isSupported) {
     // Use camera capabilities API
   }
   ```

3. **Query capabilities:**
   ```dart
   final resolutions = await capabilities.getSupportedResolutions();
   final formats = await capabilities.getSupportedFormats();
   ```

4. **Validate configurations:**
   ```dart
   final config = ARCaptureConfig(/* ... */);
   final validation = await capabilities.validateCaptureConfig(config);
   ```

## Integration with Existing Code

These examples can be integrated into existing Flutter applications by:

1. Copying the example widgets into your project
2. Adding them to your app's navigation
3. Customizing the UI to match your app's design
4. Integrating capability querying into your AR workflows

## Error Handling

All examples include comprehensive error handling for:
- Platform compatibility issues
- Camera access permissions
- Invalid configurations
- Network connectivity (for AR features)
- Hardware capability limitations


