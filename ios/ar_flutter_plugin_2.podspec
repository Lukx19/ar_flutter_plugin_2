#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint ar_flutter_plugin_2.podspec` to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'ar_flutter_plugin_2'
  s.version          = '0.6.2'
  s.summary          = 'A Flutter plugin for shared AR experiences.'
  s.description      = <<-DESC
A Flutter plugin for shared AR experiences supporting Android and iOS.
                       DESC
  s.homepage         = 'https://uhg0.com'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Hugo Lefèvre' => 'contact@uhg0.com' }
  s.source           = { :path => '.' }
  s.source_files = 'Classes/**/*'
  s.dependency 'Flutter'
  s.dependency 'GLTFSceneKit'
  s.dependency 'SwiftJWT'
  s.static_framework = true
  #s.dependency 'ARCore/CloudAnchors', '~> 1.12.0'
  #s.dependency 'ARCore', '~> 1.2.0'
 # s.dependency 'ARCore/CloudAnchors', '~> 1.36.0'  # Updated from 1.32 to 1.33 to support Apple Silicon, info here: https://github.com/google-ar/arcore-ios-sdk/issues/59#issuecomment-1219756010
  s.dependency 'ARCoreNanoPbUpdated/CloudAnchors', '~> 1.46.0.2' # ARCore does not support Firebase 11.X and the new version of nanopb (info here: https://github.com/hlefe/ar_flutter_plugin/issues/232). Temporary use of a fork of ARCore that implements this update while waiting for Google to update the official ARCore package.
  s.platform = :ios, '18.0'


  # Marker detector (native/marker_detector, shared with Android): the C ABI,
  # its AprilTag backend and the generated ARUCO_MIP_36h12 family compile
  # into this pod through the forwarding sources in
  # Classes/MarkerDetection/Native; Swift imports the ABI as the clang module
  # CMarkerDetector (native/marker_detector/include/module.modulemap).
  s.library = 'c++'
  marker_detector = '"${PODS_TARGET_SRCROOT}/../native/marker_detector'

  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386',
    'CLANG_CXX_LANGUAGE_STANDARD' => 'c++17',
    'HEADER_SEARCH_PATHS' => "$(inherited) #{marker_detector}/include\" #{marker_detector}/src\" " \
                             "#{marker_detector}/third_party/apriltag\"",
    'SWIFT_INCLUDE_PATHS' => "$(inherited) #{marker_detector}/include\"",
  }
  s.swift_version = '5.0'
end
