package com.uhg0.ar_flutter_plugin_2.storeversion

import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel

/**
 * Versions of the on-disk formats the native stores write and read, reported
 * to the app's session store descriptor (`store_version.json`). The native
 * stores keep their own internal compatibility; the app records these
 * numbers per session and refuses nothing based on them.
 *
 * Changing a native stored format bumps the matching constant here and the
 * app's store version (see the app's store migration registry).
 */
object NativeStoreFormatVersions {
    const val CHANNEL = "ar_flutter_plugin_2/store_versions"
    const val METHOD = "getStoreFormatVersions"

    /** Layout of `capture-v2-native/store` (sessions, attempts, assets, A/B root pointers). */
    // 3: deletion preserves damaged metadata outside the fenced session:
    // quarantine/<sessionHash>/<sha256(bytes)>.record (raw immutable bytes).
    const val PHOTO_STORE_LAYOUT = 3

    /** Root record schema written without a quality assessment. */
    const val PHOTO_ROOT_SCHEMA_PLAIN = 5

    /** Root record schema written with a quality assessment (assessed originals). */
    const val PHOTO_ROOT_SCHEMA_ASSESSED = 6

    /** Root record schemas the reader accepts. */
    val PHOTO_ROOT_SCHEMAS_READABLE: Set<String> =
        setOf(PHOTO_ROOT_SCHEMA_PLAIN.toString(), PHOTO_ROOT_SCHEMA_ASSESSED.toString())

    /** Layout of `capture-v2-previews`: 512/1024 px previews, optional full-resolution
     * `<key>.original.jpg` copies and deletion markers. Existing previews remain readable. */
    const val PREVIEW_CACHE_LAYOUT = 2

    /** Reply of [METHOD]: component name to version, plus the readable root schemas. */
    fun report(): Map<String, Any> = mapOf(
        "nativePhotoStore" to PHOTO_STORE_LAYOUT,
        "nativePhotoRootSchema" to PHOTO_ROOT_SCHEMA_ASSESSED,
        "nativePhotoRootSchemasReadable" to listOf(PHOTO_ROOT_SCHEMA_PLAIN, PHOTO_ROOT_SCHEMA_ASSESSED),
        "nativePreviewCache" to PREVIEW_CACHE_LAYOUT,
    )

    fun register(messenger: BinaryMessenger): MethodChannel =
        MethodChannel(messenger, CHANNEL).apply {
            setMethodCallHandler { call, result ->
                if (call.method == METHOD) result.success(report()) else result.notImplemented()
            }
        }
}
