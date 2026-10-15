import Flutter
import Foundation

/// Versions of the on-disk formats the iOS native stores write and read,
/// reported over `ar_flutter_plugin_2/store_versions` to the app's session
/// store descriptor, with the Android constants (`NativeStoreFormatVersions.kt`).
/// iOS writes the plain photo root (schema 5) only; assessed roots (schema 6)
/// are an Android debug producer and are neither written nor read here.
enum NativeStoreFormatVersions {
  static let channelName = "ar_flutter_plugin_2/store_versions"
  static let method = "getStoreFormatVersions"

  /// Layout of `capture-v2-native/store` (sessions, attempts, assets, A/B root pointers).
  static let photoStoreLayout = 2
  /// Root record schema written without a quality assessment.
  static let photoRootSchemaPlain = 5
  /// Root record schemas this platform reads.
  static let photoRootSchemasReadable = [photoRootSchemaPlain]
  /// Layout of `capture-v2-previews` (512 px and 1024 px previews, delete markers).
  static let previewCacheLayout = 1

  static func report() -> [String: Any] {
    [
      "nativePhotoStore": photoStoreLayout,
      "nativePhotoRootSchema": photoRootSchemaPlain,
      "nativePhotoRootSchemasReadable": photoRootSchemasReadable,
      "nativePreviewCache": previewCacheLayout,
    ]
  }

  static func register(messenger: FlutterBinaryMessenger) -> FlutterMethodChannel {
    let channel = FlutterMethodChannel(name: channelName, binaryMessenger: messenger)
    channel.setMethodCallHandler { call, result in
      result(call.method == method ? report() : FlutterMethodNotImplemented)
    }
    return channel
  }
}
