# Shared marker detector library (C ABI in include/marker_detector.h).
#
# Android: libcapture3d_marker_detector.so with the JNI shim, linked against
# OpenCV from the org.opencv:opencv AAR (prefab, c++_shared). Inside Gradle
# prefab provides find_package(OpenCV); outside Gradle pass
# -DC3MD_OPENCV_PREFAB_DIR=<aar>/prefab/modules/opencv_java4.
# Host: -DC3MD_WITH_OPENCV=OFF -DC3MD_BUILD_HOST_TEST=ON builds the geometry
# test without OpenCV.
cmake_minimum_required(VERSION 3.18)
project(capture3d_marker_detector CXX)

option(C3MD_WITH_OPENCV "Detect markers with aruco_nano on OpenCV" ON)
option(C3MD_BUILD_HOST_TEST "Build the host test executable" OFF)
set(C3MD_OPENCV_PREFAB_DIR "" CACHE PATH "OpenCV prefab module directory when building outside Gradle")

set(C3MD_SOURCES src/marker_geometry.cpp src/marker_detector.cpp)
if(ANDROID)
  list(APPEND C3MD_SOURCES src/marker_detector_jni.cpp)
endif()

add_library(capture3d_marker_detector SHARED ${C3MD_SOURCES})
target_include_directories(capture3d_marker_detector
  PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include
  PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src ${CMAKE_CURRENT_SOURCE_DIR}/third_party)
target_compile_features(capture3d_marker_detector PRIVATE cxx_std_17)
set_target_properties(capture3d_marker_detector PROPERTIES CXX_VISIBILITY_PRESET hidden)

if(C3MD_WITH_OPENCV)
  if(C3MD_OPENCV_PREFAB_DIR)
    add_library(c3md_opencv SHARED IMPORTED)
    set_target_properties(c3md_opencv PROPERTIES
      IMPORTED_LOCATION ${C3MD_OPENCV_PREFAB_DIR}/libs/android.${ANDROID_ABI}/libopencv_java4.so
      INTERFACE_INCLUDE_DIRECTORIES ${C3MD_OPENCV_PREFAB_DIR}/include)
    target_link_libraries(capture3d_marker_detector PRIVATE c3md_opencv)
  else()
    find_package(OpenCV REQUIRED CONFIG)
    if(TARGET OpenCV::opencv_java4)
      target_link_libraries(capture3d_marker_detector PRIVATE OpenCV::opencv_java4)
    else()
      target_include_directories(capture3d_marker_detector PRIVATE ${OpenCV_INCLUDE_DIRS})
      target_link_libraries(capture3d_marker_detector PRIVATE ${OpenCV_LIBS})
    endif()
  endif()
  target_compile_definitions(capture3d_marker_detector PRIVATE C3MD_WITH_OPENCV=1)
else()
  target_compile_definitions(capture3d_marker_detector PRIVATE C3MD_WITH_OPENCV=0)
endif()

if(ANDROID)
  target_link_libraries(capture3d_marker_detector PRIVATE jnigraphics log)
  target_compile_options(capture3d_marker_detector PRIVATE -O2 -Wall -Wextra -ffunction-sections -fdata-sections)
  target_link_options(capture3d_marker_detector PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
endif()

if(C3MD_BUILD_HOST_TEST)
  add_executable(marker_detector_host_test
    src/marker_geometry.cpp src/marker_detector.cpp test/marker_detector_host_test.cpp)
  target_include_directories(marker_detector_host_test PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include ${CMAKE_CURRENT_SOURCE_DIR}/src)
  target_compile_features(marker_detector_host_test PRIVATE cxx_std_17)
  target_compile_definitions(marker_detector_host_test PRIVATE C3MD_WITH_OPENCV=0)
endif()
