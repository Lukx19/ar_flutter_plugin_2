import 'dart:typed_data';

/// Advisory current-cut identity. Its bytes are private transport qualifiers;
/// never log them. No hint grants a canonical/style ACK or picture credit.
///
/// V1: 8-byte header, six existing 128-bit identities, twelve existing i64
/// values. No timestamp/clock domain is introduced. A received isolate-owned
/// array is borrowed without a second retained copy. Callers must treat the
/// transport-owned bytes as immutable until advisory admission completes.
final class CanonicalReadyHintV1 {
  CanonicalReadyHintV1._(this.bytes);
  static const byteLength = 200;
  static const ownerByteLength = 176;
  static const _magic = 0x31595243;
  final Uint8List bytes;
  int _ordinal(int index) =>
      ByteData.sublistView(bytes).getInt64(104 + index * 8, Endian.little);
  int get viewId => _ordinal(0);
  int get viewGeneration => _ordinal(1);
  int get bindingGeneration => _ordinal(2);
  int get sessionGeneration => _ordinal(3);
  int get groupGeneration => _ordinal(4);
  int get coverageEpoch => _ordinal(5);
  int get lifecycleSequence => _ordinal(6);
  int get operationGeneration => _ordinal(7);
  int get streamToken => _ordinal(8);
  int get transactionId => _ordinal(9);
  int get geometryRevision => _ordinal(10);
  int get lineageRevision => _ordinal(11);

  /// Returns null for malformed versions, lengths or generation selectors.
  static CanonicalReadyHintV1? decode(Object? raw) {
    if (raw is! Uint8List || raw.length != byteLength) return null;
    final data = ByteData.sublistView(raw);
    if (data.getUint32(0, Endian.little) != _magic ||
        data.getUint16(4, Endian.little) != 1 ||
        data.getUint16(6, Endian.little) != byteLength ||
        data.getInt64(104, Endian.little) < 0) return null;
    for (var index = 1; index < 12; index++) {
      final ordinal = data.getInt64(104 + index * 8, Endian.little);
      if (index == 9 ? ordinal < 0 : ordinal <= 0) return null;
    }
    return CanonicalReadyHintV1._(raw);
  }

  /// Encodes only already-owned SDK identities and revisions.
  static CanonicalReadyHintV1 encode(
      {required List<Uint8List> identities, required List<int> ordinals}) {
    if (identities.length != 6 ||
        identities.any((id) => id.length != 16) ||
        ordinals.length != 12)
      throw ArgumentError('Invalid canonical ready ownership.');
    final bytes = Uint8List(byteLength);
    final data = ByteData.sublistView(bytes);
    data.setUint32(0, _magic, Endian.little);
    data.setUint16(4, 1, Endian.little);
    data.setUint16(6, byteLength, Endian.little);
    for (var index = 0; index < 6; index++)
      bytes.setRange(8 + index * 16, 24 + index * 16, identities[index]);
    for (var index = 0; index < 12; index++)
      data.setInt64(104 + index * 8, ordinals[index], Endian.little);
    return decode(bytes) ??
        (throw ArgumentError('Invalid canonical ready selectors.'));
  }

  bool sameOwner(CanonicalReadyHintV1 other) {
    for (var index = 0; index < ownerByteLength; index++) {
      if (bytes[index] != other.bytes[index]) return false;
    }
    return true;
  }

  /// Compares one identity directly against its already-owned SDK bytes.
  bool identityEquals(int index, Uint8List expected) {
    if (expected.length != 16) return false;
    for (var byte = 0; byte < 16; byte++) {
      if (bytes[8 + index * 16 + byte] != expected[byte]) return false;
    }
    return true;
  }
}
