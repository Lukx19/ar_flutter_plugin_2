#!/bin/zsh
# Runs the iOS marker detection XCTests (Tests/MarkerDetection) on an iOS
# Simulator. SwiftPM refuses target paths outside a package root, so this
# assembles a throwaway package of symlinks: a C/C++ target CMarkerDetector
# with the marker detector sources and the AprilTag backend (the same files
# and defines the pod compiles), the Flutter-free Swift sources the marker
# code needs (Session, VisibilityGrid without the registry, MarkerDetection
# without the registry glue) and the tests; then `xcodebuild test`
# (10 minute bound).
#
# Usage: run_marker_tests.sh [simulator-udid]
# Defaults to the first available iPhone simulator. Exit status is xcodebuild's.
set -u
here=${0:A:h}
ios=${here:h:h}
plugin=${ios:h}
native=$plugin/native/marker_detector
work=$(mktemp -d "${TMPDIR:-/tmp}/marker-detection-tests.XXXXXX")
c=$work/Sources/CMarkerDetector
swift=$work/Sources/ar_flutter_plugin_2
tests=$work/Tests/MarkerDetectionTests
mkdir -p "$c/third_party" "$swift" "$tests"
ln -s "$native/include" "$c/include"
ln -s "$native/src" "$c/src"
ln -s "$native/third_party/apriltag" "$c/third_party/apriltag"
for f in ARFrameObserver CaptureFrame ARSessionLifecycle ARSessionHost ARCameraCapabilities \
         PoseBatchWire PoseStreamObserver CameraBackgroundRenderer CaptureChannelHostReplies; do
  ln -s "$ios/Classes/Session/$f.swift" "$swift/$f.swift"
done
# ARSessionHost keeps the staged world map of a restarted session, which
# reports to the native session log.
for f in WorldMap/WorldMapRelocalization WorldMap/WorldMapArchive SessionLog/NativeSessionLogEmitter \
         SessionLog/NativeSessionLogBuffer SessionLog/NativeAnomalyEmitter SessionLog/NativeSessionLogCodes; do
  ln -s "$ios/Classes/$f.swift" "$swift/${f:t}.swift"
done
for f in "$ios"/Classes/VisibilityGrid/*.swift; do
  [[ ${f:t} == VisibilityGridShellRegistry.swift ]] || ln -s "$f" "$swift/${f:t}"
done
for f in "$ios"/Classes/MarkerDetection/*.swift; do
  [[ ${f:t} == GridMarkerDetection.swift ]] || ln -s "$f" "$swift/${f:t}"
done
for f in "$here"/*Tests.swift; do ln -s "$f" "$tests/${f:t}"; done
ln -s "$ios/Tests/VisibilityGrid/VisibilityGridTestSupport.swift" "$tests/VisibilityGridTestSupport.swift"
cat > "$work/Package.swift" <<'PKG'
// swift-tools-version: 6.0
import PackageDescription

let defines: [CSetting] = [
  .define("C3MD_WITH_APRILTAG", to: "1"),
  .headerSearchPath("src"), .headerSearchPath("third_party/apriltag"), .unsafeFlags(["-w"]),
]
let package = Package(
  name: "MarkerDetectionTests",
  platforms: [.iOS(.v18)],
  targets: [
    .target(
      name: "CMarkerDetector", path: "Sources/CMarkerDetector",
      exclude: ["src/marker_detector_jni.cpp", "third_party/apriltag/README.md", "third_party/apriltag/LICENSE.md"],
      publicHeadersPath: "include", cSettings: defines,
      cxxSettings: [
        .define("C3MD_WITH_APRILTAG", to: "1"),
        .headerSearchPath("src"), .headerSearchPath("third_party/apriltag"),
      ]),
    .target(name: "ar_flutter_plugin_2", dependencies: ["CMarkerDetector"], path: "Sources/ar_flutter_plugin_2"),
    .testTarget(name: "MarkerDetectionTests", dependencies: ["ar_flutter_plugin_2"], path: "Tests/MarkerDetectionTests"),
  ],
  swiftLanguageModes: [.v5],
  cxxLanguageStandard: .cxx17
)
PKG
udid=${1:-$(xcrun simctl list devices available | grep -m1 -E '^ +iPhone' | grep -oE '[0-9A-F-]{36}')}
if [[ -z "$udid" ]]; then echo "no available iPhone simulator" >&2; exit 2; fi
cd "$work" || exit 2
perl -e 'alarm 600; exec @ARGV' xcodebuild test -scheme MarkerDetectionTests-Package \
  -destination "platform=iOS Simulator,id=$udid" -derivedDataPath "$work/dd" \
  > "$work/xcodebuild.log" 2>&1
rc=$?
grep -E "error:|Executed|TEST (SUCCEEDED|FAILED)|failed \(|XCTAssert" "$work/xcodebuild.log" | sort -u | tail -30
echo "log: $work/xcodebuild.log"
echo "exit $rc"
exit $rc
