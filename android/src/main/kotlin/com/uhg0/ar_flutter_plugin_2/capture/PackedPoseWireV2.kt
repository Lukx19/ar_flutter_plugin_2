package com.uhg0.ar_flutter_plugin_2.capture

/** Little-endian fixed records: five i64 metadata fields, confidence f32, flags/source i32,
 * then OpenCV and tracking position/quaternion/column-major transform (23 f32 each).
 * Each returned byte array is exclusively owned and immutable for its transport lifetime.
 */
internal object PackedPoseWireV2 {
    const val VERSION = "pose_batch_v2"
    const val SAMPLE_BYTES = 236
    fun putLong(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0..7) bytes[offset + i] = (value ushr (i * 8)).toByte()
    }
    fun putInt(bytes: ByteArray, offset: Int, value: Int) {
        for (i in 0..3) bytes[offset + i] = (value ushr (i * 8)).toByte()
    }
    fun putFloat(bytes: ByteArray, offset: Int, value: Float) = putInt(bytes, offset, value.toRawBits())
}
