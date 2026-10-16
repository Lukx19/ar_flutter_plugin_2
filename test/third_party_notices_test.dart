import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// `LICENSE` in the Flutter multi-license format (what the Flutter tool's
/// license collector parses into the app's NOTICES): sections separated by
/// a line of 80 hyphens, each starting with its package names and a blank
/// line.
Map<String, String> _sections(String text) {
  final separator = '\n${'-' * 80}\n';
  final sections = <String, String>{};
  for (final raw in text.split(separator)) {
    final split = raw.indexOf('\n\n');
    expect(split, greaterThan(0), reason: 'a section starts with its names');
    for (final name in raw.substring(0, split).split('\n')) {
      sections[name] = raw.substring(split + 2);
    }
  }
  return sections;
}

String _normalized(String text) =>
    text.split('\n').map((line) => line.trimRight()).join('\n').trim();

void main() {
  test(
      'LICENSE carries the plugin license and the vendored AprilTag '
      'license for the app license page', () {
    final sections = _sections(File('LICENSE').readAsStringSync());
    expect(sections.keys, containsAll(['ar_flutter_plugin_2', 'apriltag']));
    expect(sections['ar_flutter_plugin_2'], startsWith('MIT License'));
    final vendored = File(
      'native/marker_detector/third_party/apriltag/LICENSE.md',
    ).readAsStringSync();
    expect(_normalized(sections['apriltag']!), _normalized(vendored));
    expect(sections['apriltag'], contains('The Regents of The University'));
  });
}
