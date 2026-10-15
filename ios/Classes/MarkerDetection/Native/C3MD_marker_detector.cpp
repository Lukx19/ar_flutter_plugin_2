// Compiles native/marker_detector/src/marker_detector.cpp into the pod (the marker detector sources stay in
// native/marker_detector, shared with Android). Do not add code here.
#define C3MD_WITH_OPENCV 0
#define C3MD_WITH_APRILTAG 1
#include "../../../../native/marker_detector/src/marker_detector.cpp"

// The Dart binding resolves the C ABI with DynamicLibrary.process(); keep
// every exported function linked although no Swift code calls it.
__attribute__((used)) static const void* const kMarkerDetectorExports[] = {
    (const void*)&c3md_abi_version,       (const void*)&c3md_detector_available,
    (const void*)&c3md_create,            (const void*)&c3md_destroy,
    (const void*)&c3md_set_camera,        (const void*)&c3md_set_distortion,
    (const void*)&c3md_scratch,           (const void*)&c3md_set_marker_size,
    (const void*)&c3md_set_marker_size_for_id, (const void*)&c3md_set_quality,
    (const void*)&c3md_set_pose_method,   (const void*)&c3md_detect,
    (const void*)&c3md_estimate_pose,     (const void*)&c3md_records,
    (const void*)&c3md_record_count,      (const void*)&c3md_solve_points,
    (const void*)&c3md_detect_tiepoints,  (const void*)&c3md_tiepoints,
    (const void*)&c3md_timings,           (const void*)&c3md_refine_edges,
    (const void*)&c3md_diagonal_centre,   (const void*)&c3md_edge_sharpness,
    (const void*)&c3md_viewing_angle_deg, (const void*)&c3md_f16_from_float,
    (const void*)&c3md_f16_to_float,      (const void*)&c3md_distort_normalised,
    (const void*)&c3md_undistort_normalised, (const void*)&c3md_encode_record,
};
