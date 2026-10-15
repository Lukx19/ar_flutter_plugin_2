# AprilTag 3 (vendored subset)

Marker detection backend of `native/marker_detector` (`C3MD_WITH_APRILTAG`,
the iOS path). Unmodified files from the official repository.

| Item | Value |
|---|---|
| Source | https://github.com/AprilRobotics/apriltag |
| Release tag | `v3.4.5` (2025-08-29) |
| Archive | https://github.com/AprilRobotics/apriltag/archive/refs/tags/v3.4.5.tar.gz |
| Archive SHA-256 | `3749f48220db78c29c375a373861f4e1f4138e5dbae3c298584e96cfcc556515` |
| License | BSD-2-Clause, `LICENSE.md` |

Kept: the detector core (`apriltag.c`, `apriltag.h`, `apriltag_math.h`,
`apriltag_quad_thresh.c`), the `tag36h11` family, and the `common/` sources
those include (geometry, homography, images, matrices, threads, containers;
`pnm` and `image_u8x3` only because `image_u8.c` and the debug output link
them). Left out: other families, pose estimation (`apriltag_pose.c`; the
library solves poses in `src/marker_pose.cpp`), examples, Python, OpenCV
demos, CMake and tests.

The ArUco dictionary ARUCO_MIP_36h12 is not an AprilTag family; it is
generated as one (`src/aruco_mip_36h12_family.c`) by
`tools/generate_apriltag_family.py`.

To update: download the new tag's archive, record its URL and SHA-256 here,
replace the files listed above (same paths) and rerun
`test/run_marker_host_tests.sh`.
