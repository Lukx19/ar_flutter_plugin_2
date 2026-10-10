import 'dart:math' as math;
import 'dart:typed_data';

/// Shared primitives of the visibility grid binary contract.
///
/// Every binary record is little-endian, starts with a 4-byte magic (ASCII,
/// first character in the lowest byte) and a `u16` version, unless it is a
/// fixed-size record embedded in a message that carries both. Views in this
/// library read and write fields directly in caller-owned buffers, so
/// encoding and decoding allocate nothing per call.
const Endian contractEndian = Endian.little;

/// Packs a four-character ASCII tag into the `u32` stored at offset 0.
int contractMagic(String tag) {
  assert(tag.length == 4);
  return tag.codeUnitAt(0) |
      tag.codeUnitAt(1) << 8 |
      tag.codeUnitAt(2) << 16 |
      tag.codeUnitAt(3) << 24;
}

/// Outcome of validating a binary record before decoding it.
enum ContractStatus {
  ok,
  tooShort,
  badMagic,
  badVersion,
  badKind,
  badLength,
  badChecksum,
  tooLarge,
}

/// Validates the common magic, version and minimum length of a record.
ContractStatus validateMagicAndVersion(
  ByteData data,
  int offset,
  int available,
  int minimumBytes,
  int magic,
  int version,
) {
  if (available < minimumBytes || offset + minimumBytes > data.lengthInBytes) {
    return ContractStatus.tooShort;
  }
  if (data.getUint32(offset, contractEndian) != magic) {
    return ContractStatus.badMagic;
  }
  if (data.getUint16(offset + 4, contractEndian) != version) {
    return ContractStatus.badVersion;
  }
  return ContractStatus.ok;
}

/// CRC-32 (IEEE 802.3, reflected, polynomial 0xEDB88320), as the measured
/// region codec uses.
abstract final class Crc32 {
  static final Uint32List _table = _build();

  static Uint32List _build() {
    final table = Uint32List(256);
    for (var n = 0; n < 256; n++) {
      var c = n;
      for (var k = 0; k < 8; k++) {
        c = (c & 1) != 0 ? 0xedb88320 ^ (c >> 1) : c >> 1;
      }
      table[n] = c;
    }
    return table;
  }

  /// Initial running value.
  static const int start = 0xffffffff;

  /// Continues a running CRC over `bytes[from, to)`.
  static int update(int crc, Uint8List bytes, int from, int to) {
    final table = _table;
    var c = crc;
    for (var i = from; i < to; i++) {
      c = table[(c ^ bytes[i]) & 0xff] ^ (c >> 8);
    }
    return c;
  }

  /// Final CRC value of a running CRC.
  static int finish(int crc) => (crc ^ 0xffffffff) & 0xffffffff;

  /// CRC of `bytes[from, to)`.
  static int of(Uint8List bytes, int from, int to) =>
      finish(update(start, bytes, from, to));
}

/// IEEE 754 binary16 bits for [value]: round to nearest, subnormals flush to
/// zero, overflow saturates to infinity. Same algorithm as the measured
/// renderer packer.
int halfFromDouble(double value) {
  final bits = _floatBits(value);
  final sign = (bits >> 16) & 0x8000;
  final exp = ((bits >> 23) & 0xff) - 127 + 15;
  final mant = bits & 0x7fffff;
  if (exp <= 0) return sign;
  if (exp >= 31) return sign | 0x7c00;
  final rounded = (exp << 10 | mant >> 13) + ((mant >> 12) & 1);
  return sign | math.min(rounded, 0x7c00);
}

/// Value of binary16 bits [half].
double doubleFromHalf(int half) {
  final sign = (half & 0x8000) != 0 ? -1.0 : 1.0;
  final exp = (half >> 10) & 0x1f;
  final mant = half & 0x3ff;
  if (exp == 0) return sign * mant * math.pow(2, -24);
  if (exp == 31) return mant == 0 ? sign * double.infinity : double.nan;
  return sign * (1 + mant / 1024) * math.pow(2, exp - 15);
}

final ByteData _floatScratch = ByteData(4);

int _floatBits(double value) {
  _floatScratch.setFloat32(0, value);
  return _floatScratch.getUint32(0);
}

/// Octahedral encoding of a unit normal to one snorm16 component.
///
/// Returns x when [component] is 0 and y when it is 1, so callers write both
/// components without allocating a pair.
int octahedralComponent(double nx, double ny, double nz, int component) {
  final l1 = nx.abs() + ny.abs() + nz.abs();
  var x = l1 == 0 ? 0.0 : nx / l1;
  var y = l1 == 0 ? 0.0 : ny / l1;
  if (nz < 0) {
    final fx = (1 - y.abs()) * (x >= 0 ? 1 : -1);
    final fy = (1 - x.abs()) * (y >= 0 ? 1 : -1);
    x = fx;
    y = fy;
  }
  final v = component == 0 ? x : y;
  return (v.clamp(-1.0, 1.0) * 32767).round();
}

/// Decodes an octahedral normal into `out[offset, offset + 3)`.
void octahedralDecode(int ex, int ey, Float64List out, [int offset = 0]) {
  final x0 = math.max(ex / 32767, -1.0);
  final y0 = math.max(ey / 32767, -1.0);
  final z = 1 - x0.abs() - y0.abs();
  final t = math.max(-z, 0.0);
  final x = x0 + (x0 >= 0 ? -t : t);
  final y = y0 + (y0 >= 0 ? -t : t);
  final l = math.sqrt(x * x + y * y + z * z);
  out[offset] = x / l;
  out[offset + 1] = y / l;
  out[offset + 2] = z / l;
}

/// Base of fixed-size record views: a reusable cursor over caller-owned
/// bytes. [attach] moves the view without allocating.
abstract class ContractRecordView {
  ByteData _data = ByteData(0);
  int _offset = 0;

  /// Record size in bytes.
  int get recordBytes;

  ByteData get data => _data;
  int get offset => _offset;

  /// Points the view at the record starting at [offset] in [data].
  void attach(ByteData data, [int offset = 0]) {
    if (offset < 0 || offset + recordBytes > data.lengthInBytes) {
      throw RangeError.range(
          offset, 0, data.lengthInBytes - recordBytes, 'offset');
    }
    _data = data;
    _offset = offset;
  }

  /// Zeroes the attached record.
  void clear() {
    for (var i = 0; i < recordBytes; i++) {
      _data.setUint8(_offset + i, 0);
    }
  }

  int u8(int field) => _data.getUint8(_offset + field);
  void setU8(int field, int value) => _data.setUint8(_offset + field, value);
  int i16(int field) => _data.getInt16(_offset + field, contractEndian);
  void setI16(int field, int value) =>
      _data.setInt16(_offset + field, value, contractEndian);
  int u16(int field) => _data.getUint16(_offset + field, contractEndian);
  void setU16(int field, int value) =>
      _data.setUint16(_offset + field, value, contractEndian);
  int i32(int field) => _data.getInt32(_offset + field, contractEndian);
  void setI32(int field, int value) =>
      _data.setInt32(_offset + field, value, contractEndian);
  int u32(int field) => _data.getUint32(_offset + field, contractEndian);
  void setU32(int field, int value) =>
      _data.setUint32(_offset + field, value, contractEndian);
  int i64(int field) => _data.getInt64(_offset + field, contractEndian);
  void setI64(int field, int value) =>
      _data.setInt64(_offset + field, value, contractEndian);
  double f16(int field) => doubleFromHalf(u16(field));
  void setF16(int field, double value) => setU16(field, halfFromDouble(value));
  double f32(int field) => _data.getFloat32(_offset + field, contractEndian);
  void setF32(int field, double value) =>
      _data.setFloat32(_offset + field, value, contractEndian);
  double f64(int field) => _data.getFloat64(_offset + field, contractEndian);
  void setF64(int field, double value) =>
      _data.setFloat64(_offset + field, value, contractEndian);

  /// Copies [count] f32 values at [field] into [out] from [outOffset].
  void readF32s(int field, int count, List<double> out, [int outOffset = 0]) {
    for (var i = 0; i < count; i++) {
      out[outOffset + i] = f32(field + 4 * i);
    }
  }

  /// Writes [count] values of [values] from [valuesOffset] as f32 at [field].
  void writeF32s(int field, int count, List<double> values,
      [int valuesOffset = 0]) {
    for (var i = 0; i < count; i++) {
      setF32(field + 4 * i, values[valuesOffset + i]);
    }
  }

  /// Copies [count] raw bytes at [field] into [out] from [outOffset].
  void readBytes(int field, int count, List<int> out, [int outOffset = 0]) {
    for (var i = 0; i < count; i++) {
      out[outOffset + i] = u8(field + i);
    }
  }

  /// Writes [count] bytes of [values] from [valuesOffset] at [field].
  void writeBytes(int field, int count, List<int> values,
      [int valuesOffset = 0]) {
    for (var i = 0; i < count; i++) {
      setU8(field + i, values[valuesOffset + i]);
    }
  }
}
