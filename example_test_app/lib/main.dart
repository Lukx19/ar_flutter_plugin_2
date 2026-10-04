import 'package:flutter/material.dart';
import 'screens/home_screen.dart';
import 'examples/camera_capabilities_basic.dart';
import 'examples/camera_config_validation.dart';
import 'examples/camera_intrinsics_models.dart';
import 'examples/ar_with_camera_capabilities.dart';
import 'examples/unified_camera_intrinsics.dart';
import 'examples/phase5_runtime_camera_controls.dart';
import 'examples/phase7_testing_framework.dart';
import 'examples/capture_documentation_examples.dart';

void main() {
  runApp(const CameraCapabilitiesExamplesApp());
}

class CameraCapabilitiesExamplesApp extends StatelessWidget {
  const CameraCapabilitiesExamplesApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Camera Capabilities Examples',
      theme: ThemeData(
        primarySwatch: Colors.blue,
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue),
      ),
      home: const ExamplesHomeScreen(),
      routes: {
        '/basic-capabilities': (context) => const CameraCapabilitiesBasicExample(),
        '/config-validation': (context) => const CameraConfigValidationExample(),
        '/intrinsics-models': (context) => const CameraIntrinsicsModelsExample(),
        '/ar-integration': (context) => const ARWithCameraCapabilitiesExample(),
        '/unified-intrinsics': (context) => const UnifiedCameraIntrinsicsExample(),
        '/phase5-runtime-controls': (context) => const Phase5RuntimeCameraControlsWidget(),
        '/phase7-testing-framework': (context) => const Phase7TestingFrameworkExample(),
        '/capture-documentation-examples': (context) => const CaptureDocumentationExamplesWidget(),
      },
      debugShowCheckedModeBanner: false,
    );
  }
}
