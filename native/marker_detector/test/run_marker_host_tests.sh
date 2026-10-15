#!/bin/bash
# Builds and runs the marker detector host tests (macOS or Linux, no OpenCV):
# the geometry test without a detection backend, the same test on AprilTag,
# and the AprilTag accuracy test on the printed sheets of the parent
# repository (benchmarks/marker_detection/print).
#
# Usage: run_marker_host_tests.sh [build dir] [--stills <jpeg folder>]
#                                 [--write-golden <dir>]
# --stills converts the JPEG stills to BMP with sips (macOS) and reports
# what the detector finds on them. The whole run stops after 14 minutes.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
source_dir="$(cd "$here/.." && pwd)"
plugin="$(cd "$source_dir/../.." && pwd)"
repo="$(cd "$plugin/../.." && pwd)"
build="${TMPDIR:-/tmp}/marker-detector-host"
if [[ $# -gt 0 && "$1" != --* ]]; then
  build="$1"
  shift
fi
stills=""
golden=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --stills) stills="$2"; shift 2 ;;
    --write-golden) golden="$2"; shift 2 ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
done

run() {
  # Bounded below the 15-minute limit; SIGALRM (exit 142) is an overrun, not a pass.
  perl -e 'alarm 840; exec @ARGV' "$@"
}

run cmake -S "$source_dir" -B "$build" -DC3MD_WITH_OPENCV=OFF -DC3MD_WITH_APRILTAG=ON \
  -DC3MD_BUILD_HOST_TEST=ON -DCMAKE_BUILD_TYPE=Release > "$build.configure.log" 2>&1 \
  || { tail -20 "$build.configure.log"; exit 1; }
run cmake --build "$build" -j 8 > "$build.build.log" 2>&1 || { grep -E "error" "$build.build.log" | head -20; exit 1; }

golden_file="$plugin/test_assets/visibility_grid/ingest_markers.bin"
rc=0
run "$build/marker_detector_host_test" "$golden_file" | tail -4 || rc=1
run "$build/marker_detector_host_test_apriltag" "$golden_file" | tail -4 || rc=1

args=(--codes "$repo/benchmarks/marker_detection/print/aruco_mip_36h12.json"
      --sheets "$repo/benchmarks/marker_detection/print")
if [[ -n "$stills" ]]; then
  bmp="$build/stills_bmp"
  mkdir -p "$bmp"
  for jpg in "$stills"/*.jpg; do
    name="$(basename "$jpg" .jpg)"
    [[ -f "$bmp/$name.bmp" ]] || sips -s format bmp "$jpg" --out "$bmp/$name.bmp" > /dev/null
  done
  args+=(--stills "$bmp")
fi
if [[ -n "$golden" ]]; then
  mkdir -p "$golden"
  args+=(--write-golden "$golden")
fi
run "$build/marker_detector_accuracy_test_apriltag" "${args[@]}" || rc=$?
if [[ $rc -eq 142 ]]; then
  echo "STOPPED: 14-minute bound reached; not passing evidence" >&2
fi
exit $rc
