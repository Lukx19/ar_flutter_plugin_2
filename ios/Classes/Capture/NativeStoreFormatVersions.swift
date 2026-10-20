import Flutter
import Foundation

/// Versions of the on-disk formats the iOS native stores write and read,
/// reported over `ar_flutter_plugin_2/store_versions` to the app's session
/// store descriptor, with the Android constants (`NativeStoreFormatVersions.kt`).
/// iOS writes the plain photo root (schema 5) and, for a burst shot,
/// the shot root (schema 7); assessed roots (schema 6) are an Android debug
/// producer and are neither written nor read here.
enum NativeStoreFormatVersions {
  static let channelName = "ar_flutter_plugin_2/store_versions"
  static let method = "getStoreFormatVersions"

  /// Layout of `capture-v2-native/store` (sessions, attempts, assets, A/B
  /// root pointers). 3: an asset folder may also hold the images after the
  /// first one of a burst shot (`shot-image-<index>.<sha>.blob`) and
  /// its record (`shot-record.<sha>.blob`); Android writes layout 2. 4: the
  /// shot record is `capture3d-shot-v2` (per image `T_world_cam`, OpenCV
  /// camera axes); layout 3 records (`capture3d-shot-v1`, ARKit camera
  /// transform) are read and converted, never rewritten.
  static let photoStoreLayout = 4
  /// Root record schema written without a quality assessment.
  static let photoRootSchemaPlain = 5
  /// Root record schema of a burst shot (schema 5 plus shot lines).
  static let photoRootSchemaShot = 7
  /// Root record schemas this platform reads.
  static let photoRootSchemasReadable = [photoRootSchemaPlain, photoRootSchemaShot]
  /// Layout of `capture-v2-previews` (512 px and 1024 px previews, delete markers).
  static let previewCacheLayout = 1
  /// Layout of `capture-v2-native/budget` (ledger, reservations, verified
  /// reclaims). 2: a settlement writes `reservations-v2/<token>.settled`
  /// before the ledger moves and deletes it after the reservation files, so
  /// a crash between them is finished by recovery; Android does not report
  /// this component.
  static let storageBudgetLayout = 2

  static func report() -> [String: Any] {
    [
      "nativePhotoStore": photoStoreLayout,
      "nativePhotoRootSchema": photoRootSchemaPlain,
      "nativePhotoRootSchemasReadable": photoRootSchemasReadable,
      "nativePhotoShotRootSchema": photoRootSchemaShot,
      "nativePreviewCache": previewCacheLayout,
      "nativeStorageBudget": storageBudgetLayout,
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
