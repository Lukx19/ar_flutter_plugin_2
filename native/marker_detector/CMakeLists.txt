# Shared marker detector library (C ABI in include/marker_detector.h).
#
# Detection backend: AprilTag 3 (third_party/apriltag, BSD-2-Clause) with the
# generated ARUCO_MIP_36h12 family (C3MD_WITH_APRILTAG, default on). Android:
# libcapture3d_marker_detector.so with the JNI shim (android/src/main/cpp
# adds this folder when the plugin is built with CAPTURE3D_GRID_MARKERS).
# Apple: a static library (the iOS pod compiles the same sources itself, see
# ios/ar_flutter_plugin_2.podspec).
#
# Host: -DC3MD_BUILD_HOST_TEST=ON builds the geometry test without a backend
# and, with C3MD_WITH_APRILTAG, the same test, the accuracy test and the
# shared library of the synthetic marker scene runner
# (benchmarks/marker_detection/synthetic_scene) on AprilTag.
cmake_minimum_required(VERSION 3.18)
project(capture3d_marker_detector C CXX)

option(C3MD_WITH_APRILTAG "Detect markers with AprilTag 3" ON)
option(C3MD_BUILD_HOST_TEST "Build the host test executables" OFF)

set(C3MD_SOURCES src/marker_geometry.cpp src/marker_pose.cpp src/marker_detector.cpp)
if(ANDROID)
  list(APPEND C3MD_SOURCES src/marker_detector_jni.cpp)
endif()

# AprilTag core, the common/ helpers it needs, tag36h11 and the generated
# ARUCO_MIP_36h12 family.
set(C3MD_APRILTAG_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/apriltag)
set(C3MD_APRILTAG_SOURCES
  ${C3MD_APRILTAG_DIR}/apriltag.c
  ${C3MD_APRILTAG_DIR}/apriltag_quad_thresh.c
  ${C3MD_APRILTAG_DIR}/tag36h11.c
  ${C3MD_APRILTAG_DIR}/common/g2d.c
  ${C3MD_APRILTAG_DIR}/common/homography.c
  ${C3MD_APRILTAG_DIR}/common/image_u8.c
  ${C3MD_APRILTAG_DIR}/common/image_u8_parallel.c
  ${C3MD_APRILTAG_DIR}/common/image_u8x3.c
  ${C3MD_APRILTAG_DIR}/common/matd.c
  ${C3MD_APRILTAG_DIR}/common/pnm.c
  ${C3MD_APRILTAG_DIR}/common/pthreads_cross.c
  ${C3MD_APRILTAG_DIR}/common/svd22.c
  ${C3MD_APRILTAG_DIR}/common/time_util.c
  ${C3MD_APRILTAG_DIR}/common/unionfind.c
  ${C3MD_APRILTAG_DIR}/common/workerpool.c
  ${C3MD_APRILTAG_DIR}/common/zarray.c
  ${C3MD_APRILTAG_DIR}/common/zmaxheap.c
  ${CMAKE_CURRENT_SOURCE_DIR}/src/aruco_mip_36h12_family.c)

if(C3MD_WITH_APRILTAG)
  list(APPEND C3MD_SOURCES src/marker_apriltag.cpp ${C3MD_APRILTAG_SOURCES})
  set_source_files_properties(${C3MD_APRILTAG_SOURCES} PROPERTIES COMPILE_OPTIONS "-w")
endif()

if(APPLE)
  add_library(capture3d_marker_detector STATIC ${C3MD_SOURCES})
else()
  add_library(capture3d_marker_detector SHARED ${C3MD_SOURCES})
endif()
target_include_directories(capture3d_marker_detector
  PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include
  PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src ${CMAKE_CURRENT_SOURCE_DIR}/third_party)
target_compile_features(capture3d_marker_detector PRIVATE cxx_std_17)
set_target_properties(capture3d_marker_detector PROPERTIES CXX_VISIBILITY_PRESET hidden C_VISIBILITY_PRESET hidden)

if(C3MD_WITH_APRILTAG)
  target_include_directories(capture3d_marker_detector PRIVATE ${C3MD_APRILTAG_DIR})
  target_compile_definitions(capture3d_marker_detector PRIVATE C3MD_WITH_APRILTAG=1)
else()
  target_compile_definitions(capture3d_marker_detector PRIVATE C3MD_WITH_APRILTAG=0)
endif()

if(ANDROID)
  target_link_libraries(capture3d_marker_detector PRIVATE jnigraphics log)
  target_compile_options(capture3d_marker_detector PRIVATE -O2 -Wall -Wextra -ffunction-sections -fdata-sections)
  target_link_options(capture3d_marker_detector PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
endif()

if(C3MD_BUILD_HOST_TEST)
  # Geometry only: detection calls report C3MD_ERROR_UNAVAILABLE.
  add_executable(marker_detector_host_test
    src/marker_geometry.cpp src/marker_pose.cpp src/marker_detector.cpp test/marker_detector_host_test.cpp)
  target_include_directories(marker_detector_host_test PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include ${CMAKE_CURRENT_SOURCE_DIR}/src)
  target_compile_features(marker_detector_host_test PRIVATE cxx_std_17)
  target_compile_definitions(marker_detector_host_test PRIVATE C3MD_WITH_APRILTAG=0)

  if(C3MD_WITH_APRILTAG)
    foreach(test marker_detector_host_test marker_detector_accuracy_test)
      add_executable(${test}_apriltag
        src/marker_geometry.cpp src/marker_pose.cpp src/marker_detector.cpp src/marker_apriltag.cpp
        ${C3MD_APRILTAG_SOURCES} test/${test}.cpp)
      target_include_directories(${test}_apriltag PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/include ${CMAKE_CURRENT_SOURCE_DIR}/src ${C3MD_APRILTAG_DIR})
      target_compile_features(${test}_apriltag PRIVATE cxx_std_17)
      target_compile_definitions(${test}_apriltag PRIVATE C3MD_WITH_APRILTAG=1)
    endforeach()
    find_package(ZLIB REQUIRED)  # PNG goldens of the iOS XCTest
    target_link_libraries(marker_detector_accuracy_test_apriltag PRIVATE ZLIB::ZLIB)

    # The C ABI as a host shared library for the synthetic scene runner
    # (ctypes); the same sources and defines as the Android library without
    # the JNI shim, plus the test-only pose probe (both IPPE solutions of one
    # detection) the scene check reads.
    add_library(marker_detector_scene_host SHARED
      src/marker_geometry.cpp src/marker_pose.cpp src/marker_detector.cpp src/marker_apriltag.cpp
      ${C3MD_APRILTAG_SOURCES} test/marker_scene_pose_probe.cpp)
    target_include_directories(marker_detector_scene_host PRIVATE
      ${CMAKE_CURRENT_SOURCE_DIR}/include ${CMAKE_CURRENT_SOURCE_DIR}/src ${C3MD_APRILTAG_DIR})
    target_compile_features(marker_detector_scene_host PRIVATE cxx_std_17)
    target_compile_definitions(marker_detector_scene_host PRIVATE C3MD_WITH_APRILTAG=1)
    set_target_properties(marker_detector_scene_host PROPERTIES CXX_VISIBILITY_PRESET hidden C_VISIBILITY_PRESET hidden)
  endif()
endif()
