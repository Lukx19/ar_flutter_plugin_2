import 'package:example_test_app/main.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('Capture documentation opens from the example catalog',
      (tester) async {
    await tester.pumpWidget(const CameraCapabilitiesExamplesApp());
    expect(find.text('Camera Capabilities Examples'), findsOneWidget);

    final documentation = find.text('Documentation & Examples');
    await tester.ensureVisible(documentation);
    await tester.tap(documentation);
    await tester.pumpAndSettle();

    expect(find.text('Capture Documentation & Examples'), findsOneWidget);
    expect(find.text('API Documentation'), findsWidgets);
    expect(tester.takeException(), isNull);
  });
}
