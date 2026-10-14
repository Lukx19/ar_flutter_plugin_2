@testable import ar_flutter_plugin_2
import XCTest

/// The plugin's internal types are reachable from `RunnerTests`: the pod is
/// compiled with testability in Debug and linked into the host app, so the
/// tests of every plugin folder can use `@testable import ar_flutter_plugin_2`.
final class PluginModuleAccessTests: XCTestCase {
  func testFrameSourceRegistryIsReachableAndReportsItsCurrentSource() {
    let registry = ARFrameSourceRegistry.shared
    var calls = 0
    var reported: ARFrameSource?
    let id = registry.observe { source in
      calls += 1
      reported = source
    }
    registry.stopObserving(id)
    XCTAssertEqual(calls, 1, "observe calls the listener once with the current source")
    XCTAssertTrue(reported === registry.current)
  }
}
