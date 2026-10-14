#!/bin/zsh
# Runs the grid renderer XCTests (Tests/GridRenderer) outside the app.
#
# SwiftPM refuses target paths outside a package root, so this assembles a
# throwaway package of symlinks to the Flutter- and ARKit-free renderer
# sources (Classes/GridRenderer without GridRendererChannels.swift, plus the
# contract layout of Classes/VisibilityGrid) and the test files (without
# GridRendererChannelTests.swift, which needs Flutter and runs in the app's
# RunnerTests). The Metal style tests draw offscreen on the host GPU.
#
# Usage: run_grid_renderer_tests.sh macos [swift test arguments]
#        run_grid_renderer_tests.sh ios [simulator-udid] [-only-testing:...]
# macos runs `swift test` on the Mac; ios runs `xcodebuild test` on an iOS
# Simulator (default: the first available iPhone). Both stop after 14 minutes;
# exit 142 is an overrun, not a pass.
set -u
here=${0:A:h}
classes=${here:h:h}/Classes
goldens=${here:h:h:h}/test_assets/visibility_grid
platform=${1:-macos}
shift $(( $# > 0 ? 1 : 0 ))
work=$(mktemp -d "${TMPDIR:-/tmp}/grid-renderer-tests.XXXXXX")
mkdir -p "$work/Sources/ar_flutter_plugin_2" "$work/Tests/GridRendererTests"
for f in "$classes"/GridRenderer/*.swift "$classes"/GridRenderer/Quest/*.swift; do
  [[ ${f:t} == GridRendererChannels.swift ]] && continue
  ln -s "$f" "$work/Sources/ar_flutter_plugin_2/${f:t}"
done
ln -s "$classes/VisibilityGrid/VisibilityGridContractLayout.swift" "$work/Sources/ar_flutter_plugin_2/"
for f in "$here"/*.swift; do
  [[ ${f:t} == GridRendererChannelTests.swift ]] && continue
  ln -s "$f" "$work/Tests/GridRendererTests/${f:t}"
done
cat > "$work/Package.swift" <<'PKG'
// swift-tools-version: 6.0
import PackageDescription

let package = Package(
  name: "GridRendererHostTests",
  platforms: [.macOS(.v14), .iOS(.v18)],
  targets: [
    .target(name: "ar_flutter_plugin_2", path: "Sources/ar_flutter_plugin_2"),
    .testTarget(name: "GridRendererTests", dependencies: ["ar_flutter_plugin_2"], path: "Tests/GridRendererTests"),
  ],
  swiftLanguageModes: [.v5]
)
PKG
cd "$work" || exit 2
rc=0
if [[ $platform == macos ]]; then
  VISIBILITY_GRID_GOLDENS="$goldens" perl -e 'alarm 840; exec @ARGV' swift test "$@" > "$work/test.log" 2>&1 || rc=$?
else
  udid=${1:-$(xcrun simctl list devices available | grep -m1 -E '^ +iPhone' | grep -oE '[0-9A-F-]{36}')}
  shift $(( $# > 0 ? 1 : 0 ))
  if [[ -z "$udid" ]]; then echo "no available iPhone simulator" >&2; exit 2; fi
  perl -e 'alarm 840; exec @ARGV' xcodebuild test -scheme GridRendererHostTests-Package \
    -destination "platform=iOS Simulator,id=$udid" -derivedDataPath "$work/dd" "$@" \
    > "$work/test.log" 2>&1 || rc=$?
fi
grep -E "error:|warning: .*GridRenderer|Executed|TEST (SUCCEEDED|FAILED)|failed|passed after|Test Suite .* (passed|failed)" "$work/test.log" \
  | grep -v "^Test Case .* passed" | sort -u | tail -40
if [[ $rc -eq 142 ]]; then echo "STOPPED: 14-minute bound reached; not passing evidence" >&2; fi
echo "log: $work/test.log"
echo "exit $rc"
exit $rc
