#!/bin/bash
# Runs the camera frame convention golden (FrameConventionGoldenTests) on the
# macOS host. The pose wire encoder (Classes/Session/PoseBatchWire.swift) uses
# Foundation only, so a temporary Swift package links it and the test by
# symlink; the iOS sources stay the single source. The golden JSON is read
# from the plugin's test_assets through the test file's resolved path.
# Usage: run_frame_convention_host_tests.sh [swift test arguments]. The run
# stops after 14 minutes; exit 142 is an overrun, not passing evidence.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
classes="$(cd "$here/../../Classes/Session" && pwd)"
work="$(mktemp -d "${TMPDIR:-/tmp}/frame-convention-host.XXXXXX")"
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/Sources/ar_flutter_plugin_2" "$work/Tests/FrameConventionTests"
ln -s "$classes/PoseBatchWire.swift" "$work/Sources/ar_flutter_plugin_2/"
ln -s "$here/FrameConventionGoldenTests.swift" "$work/Tests/FrameConventionTests/"

cat > "$work/Package.swift" <<'EOF'
// swift-tools-version: 5.9
import PackageDescription

let package = Package(
  name: "FrameConventionHost",
  platforms: [.macOS(.v13)],
  targets: [
    .target(name: "ar_flutter_plugin_2", path: "Sources/ar_flutter_plugin_2"),
    .testTarget(name: "FrameConventionTests", dependencies: ["ar_flutter_plugin_2"], path: "Tests/FrameConventionTests"),
  ],
  swiftLanguageVersions: [.v5]
)
EOF

cd "$work"
rc=0
perl -e 'alarm 840; exec @ARGV' swift test "$@" || rc=$?
if [[ $rc -eq 142 ]]; then
  echo "STOPPED: 14-minute bound reached; not passing evidence" >&2
fi
exit $rc
