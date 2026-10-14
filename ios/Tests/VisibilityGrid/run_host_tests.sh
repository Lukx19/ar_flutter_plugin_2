#!/bin/bash
# Runs the visibility grid ingest XCTests on the macOS host.
#
# The shell's logic files use only Foundation, simd and CryptoKit; the ARKit
# and Flutter adapters (ARKitIngestAdapters.swift,
# VisibilityGridShellRegistry.swift) are left out. A temporary Swift package
# links the logic files and these tests by symlink, so nothing is copied and
# the iOS sources stay the single source. Usage: run_host_tests.sh [swift test
# arguments, for example --filter IngestGoldenTests]. The run stops after 14
# minutes.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
classes="$(cd "$here/../../Classes/VisibilityGrid" && pwd)"
goldens="$(cd "$here/../../../test_assets/visibility_grid" && pwd)"
work="$(mktemp -d "${TMPDIR:-/tmp}/visibility-grid-host.XXXXXX")"
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/Sources/ar_flutter_plugin_2" "$work/Tests/VisibilityGridTests"
for file in "$classes"/*.swift; do
  case "$(basename "$file")" in
    ARKitIngestAdapters.swift | VisibilityGridShellRegistry.swift) ;;
    *) ln -s "$file" "$work/Sources/ar_flutter_plugin_2/" ;;
  esac
done
for file in "$here"/*.swift; do
  ln -s "$file" "$work/Tests/VisibilityGridTests/"
done

cat > "$work/Package.swift" <<'EOF'
// swift-tools-version: 5.9
import PackageDescription

let package = Package(
  name: "VisibilityGridHost",
  platforms: [.macOS(.v13)],
  targets: [
    .target(name: "ar_flutter_plugin_2", path: "Sources/ar_flutter_plugin_2"),
    .testTarget(name: "VisibilityGridTests", dependencies: ["ar_flutter_plugin_2"], path: "Tests/VisibilityGridTests"),
  ],
  swiftLanguageVersions: [.v5]
)
EOF

cd "$work"
# Bounded below the 15-minute limit; SIGALRM (exit 142) is an overrun, not a pass.
rc=0
VISIBILITY_GRID_GOLDENS="$goldens" perl -e 'alarm 840; exec @ARGV' swift test "$@" || rc=$?
if [[ $rc -eq 142 ]]; then
  echo "STOPPED: 14-minute bound reached; not passing evidence" >&2
fi
exit $rc
