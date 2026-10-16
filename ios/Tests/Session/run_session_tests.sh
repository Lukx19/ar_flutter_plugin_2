#!/bin/zsh
# Runs the iOS session host XCTests (Tests/Session) on an iOS Simulator.
# SwiftPM refuses target paths outside a package root, so this assembles a
# throwaway package of symlinks to the Flutter-free Classes/Session sources
# (plus the world map and native session log sources they use)
# and the test files, then runs `xcodebuild test` (10 minute bound).
#
# Usage: run_session_tests.sh [simulator-udid]
# Defaults to the first available iPhone simulator. Exit status is xcodebuild's.
set -u
here=${0:A:h}
classes=${here:h:h}/Classes/Session
work=$(mktemp -d "${TMPDIR:-/tmp}/session-host-tests.XXXXXX")
mkdir -p "$work/Sources/ar_flutter_plugin_2" "$work/Tests/SessionTests"
for f in ARFrameObserver CaptureFrame ARSessionLifecycle ARSessionHost ARCameraCapabilities \
         PoseBatchWire PoseStreamObserver CameraBackgroundRenderer \
         CaptureChannelHostReplies; do
  ln -s "$classes/$f.swift" "$work/Sources/ar_flutter_plugin_2/$f.swift"
done
# The session host stages saved world maps (WorldMap/) whose relocalization
# monitor logs through the native session log (SessionLog/); the Flutter
# channel files stay out.
for f in WorldMap/WorldMapRelocalization WorldMap/WorldMapArchive \
         SessionLog/NativeSessionLogBuffer SessionLog/NativeSessionLogEmitter SessionLog/NativeAnomalyEmitter; do
  ln -s "${classes:h}/$f.swift" "$work/Sources/ar_flutter_plugin_2/${f:t}.swift"
done
# Test files that need Flutter (the capture view) or the render loop with its
# grid overlay registry run only in RunnerTests.
for f in "$here"/*Tests.swift; do
  grep -q '^import Flutter' "$f" && continue
  [[ ${f:t} == CaptureViewFrameStatsTests.swift ]] && continue
  ln -s "$f" "$work/Tests/SessionTests/${f:t}"
done
cat > "$work/Package.swift" <<'PKG'
// swift-tools-version: 6.0
import PackageDescription

let package = Package(
  name: "SessionHostTests",
  platforms: [.iOS(.v18)],
  targets: [
    .target(name: "ar_flutter_plugin_2", path: "Sources/ar_flutter_plugin_2"),
    .testTarget(name: "SessionTests", dependencies: ["ar_flutter_plugin_2"], path: "Tests/SessionTests"),
  ],
  swiftLanguageModes: [.v5]
)
PKG
udid=${1:-$(xcrun simctl list devices available | grep -m1 -E '^ +iPhone' | grep -oE '[0-9A-F-]{36}')}
if [[ -z "$udid" ]]; then echo "no available iPhone simulator" >&2; exit 2; fi
cd "$work" || exit 2
perl -e 'alarm 600; exec @ARGV' xcodebuild test -scheme SessionHostTests-Package \
  -destination "platform=iOS Simulator,id=$udid" -derivedDataPath "$work/dd" \
  > "$work/xcodebuild.log" 2>&1
rc=$?
grep -E "error:|Executed|TEST (SUCCEEDED|FAILED)|failed \(" "$work/xcodebuild.log" | sort -u | tail -20
echo "log: $work/xcodebuild.log"
echo "exit $rc"
exit $rc
