import 'dart:io';
import 'package:flutter/material.dart';

class ExamplesHomeScreen extends StatelessWidget {
  const ExamplesHomeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Camera Capabilities Examples'),
        backgroundColor: Theme.of(context).colorScheme.primaryContainer,
        foregroundColor: Theme.of(context).colorScheme.onPrimaryContainer,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Header section
            _buildHeaderSection(context),
            const SizedBox(height: 24),
            
            // Platform compatibility warning
            if (!Platform.isAndroid) _buildPlatformWarning(context),
            if (!Platform.isAndroid) const SizedBox(height: 24),
            
            // Examples grid
            _buildExamplesGrid(context),
          ],
        ),
      ),
    );
  }

  Widget _buildHeaderSection(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'AR Flutter Plugin',
          style: Theme.of(context).textTheme.headlineMedium?.copyWith(
            fontWeight: FontWeight.bold,
            color: Theme.of(context).colorScheme.primary,
          ),
        ),
        const SizedBox(height: 8),
        Text(
          'Camera Capabilities API Examples',
          style: Theme.of(context).textTheme.titleLarge?.copyWith(
            color: Theme.of(context).colorScheme.onSurface,
          ),
        ),
        const SizedBox(height: 12),
        Text(
          'Explore the new camera capabilities API that allows querying device camera features, validating configurations, and optimizing AR performance.',
          style: Theme.of(context).textTheme.bodyLarge?.copyWith(
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
        ),
      ],
    );
  }

  Widget _buildPlatformWarning(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.errorContainer,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(
          color: Theme.of(context).colorScheme.error.withOpacity(0.3),
        ),
      ),
      child: Row(
        children: [
          Icon(
            Icons.warning_rounded,
            color: Theme.of(context).colorScheme.onErrorContainer,
            size: 24,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Platform Compatibility',
                  style: Theme.of(context).textTheme.titleMedium?.copyWith(
                    color: Theme.of(context).colorScheme.onErrorContainer,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  'Camera capabilities API is currently supported on Android only. On other platforms, examples will show platform compatibility messages.',
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                    color: Theme.of(context).colorScheme.onErrorContainer,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildExamplesGrid(BuildContext context) {
    final examples = [
      ExampleInfo(
        title: 'Basic Capabilities',
        subtitle: 'Device capability querying',
        description: 'Query supported resolutions, formats, ISO ranges, and exposure settings. Test specific capabilities on your device.',
        icon: Icons.camera_alt_rounded,
        route: '/basic-capabilities',
        color: Colors.blue,
      ),
      ExampleInfo(
        title: 'Config Validation',
        subtitle: 'Configuration testing',
        description: 'Validate AR capture configurations, see validation results, and get recommended settings for optimal performance.',
        icon: Icons.settings_rounded,
        route: '/config-validation',
        color: Colors.green,
      ),
      ExampleInfo(
        title: 'Camera Models',
        subtitle: 'Intrinsics demonstration',
        description: 'Explore camera intrinsic models like focal length, principal point, image size, and resolution calculations.',
        icon: Icons.analytics_rounded,
        route: '/intrinsics-models',
        color: Colors.orange,
      ),
      ExampleInfo(
        title: 'AR Integration',
        subtitle: 'Full AR with capabilities',
        description: 'See how camera capabilities integrate with AR sessions, including dynamic configuration and performance monitoring.',
        icon: Icons.view_in_ar_rounded,
        route: '/ar-integration',
        color: Colors.purple,
      ),
      ExampleInfo(
        title: 'Unified Intrinsics',
        subtitle: 'Phase 3: Unified camera intrinsics system',
        description: 'Explore the new unified camera intrinsics API with structured data types, validation, and OpenCV compatibility.',
        icon: Icons.grid_view_rounded,
        route: '/unified-intrinsics',
        color: Colors.teal,
      ),
      ExampleInfo(
        title: 'Runtime Camera Controls',
        subtitle: 'Phase 5: Advanced camera parameter control',
        description: 'Real-time camera control with ISO, exposure, focus, white balance, scene modes, and camera parameter profiles.',
        icon: Icons.tune_rounded,
        route: '/phase5-runtime-controls',
        color: Colors.indigo,
      ),
      ExampleInfo(
        title: 'Testing Framework',
        subtitle: 'Phase 7: Comprehensive testing & validation',
        description: 'Advanced testing framework with unit tests, integration tests, platform validation, cross-platform compatibility, and performance testing.',
        icon: Icons.fact_check_rounded,
        route: '/phase7-testing-framework',
        color: Colors.red,
      ),
      ExampleInfo(
        title: 'Documentation & Examples',
        subtitle: 'Phase 8: Complete documentation system',
        description: 'Comprehensive documentation generation, usage examples, integration guides, performance optimization, and developer tools.',
        icon: Icons.library_books_rounded,
        route: '/phase8-documentation-examples',
        color: Colors.teal,
      ),
    ];

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'Available Examples',
          style: Theme.of(context).textTheme.titleLarge?.copyWith(
            fontWeight: FontWeight.bold,
          ),
        ),
        const SizedBox(height: 16),
        ...examples.map((example) => Padding(
          padding: const EdgeInsets.only(bottom: 16),
          child: _buildExampleCard(context, example),
        )),
      ],
    );
  }

  Widget _buildExampleCard(BuildContext context, ExampleInfo example) {
    return Card(
      elevation: 2,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: InkWell(
        onTap: () => Navigator.pushNamed(context, example.route),
        borderRadius: BorderRadius.circular(16),
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Row(
            children: [
              // Icon
              Container(
                width: 60,
                height: 60,
                decoration: BoxDecoration(
                  color: example.color.withOpacity(0.1),
                  borderRadius: BorderRadius.circular(16),
                ),
                child: Icon(
                  example.icon,
                  color: example.color,
                  size: 30,
                ),
              ),
              const SizedBox(width: 16),
              
              // Content
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      example.title,
                      style: Theme.of(context).textTheme.titleLarge?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      example.subtitle,
                      style: Theme.of(context).textTheme.titleSmall?.copyWith(
                        color: example.color,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      example.description,
                      style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                        height: 1.4,
                      ),
                    ),
                  ],
                ),
              ),
              
              // Arrow
              Container(
                width: 40,
                height: 40,
                decoration: BoxDecoration(
                  color: Theme.of(context).colorScheme.surfaceVariant,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Icon(
                  Icons.arrow_forward_rounded,
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                  size: 20,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class ExampleInfo {
  final String title;
  final String subtitle;
  final String description;
  final IconData icon;
  final String route;
  final Color color;

  const ExampleInfo({
    required this.title,
    required this.subtitle,
    required this.description,
    required this.icon,
    required this.route,
    required this.color,
  });
}
