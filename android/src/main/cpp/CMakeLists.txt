cmake_minimum_required(VERSION 3.18.1)
project(capture3d_safe_fs_v2)
add_library(capture3d_safe_fs_v2 SHARED safe_filesystem_v2.cpp)
target_compile_features(capture3d_safe_fs_v2 PRIVATE cxx_std_17)
target_compile_options(capture3d_safe_fs_v2 PRIVATE -Wall -Wextra -Werror)

# Marker detector of the visibility grid (shared C library, AprilTag
# backend); only with CAPTURE3D_GRID_MARKERS (android/build.gradle passes it).
option(CAPTURE3D_GRID_MARKERS "Build the marker detector library" OFF)
if(CAPTURE3D_GRID_MARKERS)
  add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/marker_detector
    ${CMAKE_CURRENT_BINARY_DIR}/marker_detector)
endif()
