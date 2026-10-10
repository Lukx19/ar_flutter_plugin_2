# Native libraries of the visibility grid. Not yet referenced by the Android
# build (android/build.gradle builds src/main/cpp only); each library is added
# behind its own option, all off by default.
cmake_minimum_required(VERSION 3.18)
project(capture3d_visibility_grid_native C CXX)

option(CAPTURE3D_GRID_MARKERS "Build the marker detector library" OFF)
option(CAPTURE3D_GRID_NATIVE_ASSOCIATION "Build the native grid fusion experiment" OFF)
option(CAPTURE3D_GRID_CONTRACT_LAYOUT_CHECK "Build the contract layout check" OFF)

add_library(visibility_grid_contract INTERFACE)
target_include_directories(visibility_grid_contract INTERFACE
  ${CMAKE_CURRENT_SOURCE_DIR}/visibility_grid_contract/include
  ${CMAKE_CURRENT_SOURCE_DIR}/marker_detector/include)

if(CAPTURE3D_GRID_CONTRACT_LAYOUT_CHECK)
  add_executable(contract_layout_check
    visibility_grid_contract/test/contract_layout_check.c)
  target_link_libraries(contract_layout_check PRIVATE visibility_grid_contract)
  set_property(TARGET contract_layout_check PROPERTY C_STANDARD 11)
endif()

if(CAPTURE3D_GRID_MARKERS AND EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/marker_detector/CMakeLists.txt)
  add_subdirectory(marker_detector)
endif()

if(CAPTURE3D_GRID_NATIVE_ASSOCIATION AND EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/grid_fusion/CMakeLists.txt)
  add_subdirectory(grid_fusion)
endif()
