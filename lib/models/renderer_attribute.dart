import 'dart:typed_data';

/// Saved-view observation classes, independent of aggregate completion.
abstract final class RendererObservationPalette {
  static const zeroArgb = 0xffff4545;
  static const oneArgb = 0xffffbf3f;
  static const twoPlusArgb = 0xff44cc66;
  static const unavailableArgb = 0xff999999;
}

/// Negotiated optional display attributes, separate from canonical/style ACKs.
final class RendererAttributeHeaderV1 {
  RendererAttributeHeaderV1._(this.bytes);

  static const headerBytes = 256;
  static const rowBytes = 24;
  static const pageBytes = 16384 - 80;
  static const idsPerPage = (pageBytes - headerBytes) ~/ 8;
  static const rowsPerPage = (pageBytes - headerBytes) ~/ rowBytes;
  static const select = 1, page = 2, commit = 3, status = 4, cancel = 5;
  static const patches = 1, balls = 2;
  static const observations = 1, directionIdentity = 2;
  static const _magic = 0x31544152;
  final Uint8List bytes;
  ByteData get _data => ByteData.sublistView(bytes);
  int get operation => bytes[5];
  int get rendererGeneration => _data.getInt64(124, Endian.little);
  int get selectionGeneration => _data.getInt64(132, Endian.little);
  int get shape => _data.getUint32(140, Endian.little);
  int get color => _data.getUint32(144, Endian.little);
  int get total => _data.getUint32(148, Endian.little);
  int get pageIndex => _data.getUint32(152, Endian.little);
  int get pageCount => _data.getUint32(156, Endian.little);
  int get first => _data.getUint32(160, Endian.little);
  int get count => _data.getUint32(164, Endian.little);

  factory RendererAttributeHeaderV1.create(
      {required Uint8List styleHeader,
      required int shape,
      required int color,
      int rendererGeneration = 0,
      int selectionGeneration = 0,
      int total = 0}) {
    if (styleHeader.length != 116) throw ArgumentError('Style header length.');
    final bytes = Uint8List(headerBytes)..setRange(8, 124, styleHeader);
    final data = ByteData.sublistView(bytes);
    data.setUint32(0, _magic, Endian.little);
    bytes[4] = 1;
    bytes[5] = select;
    data.setUint16(6, headerBytes, Endian.little);
    data.setInt64(124, rendererGeneration, Endian.little);
    data.setInt64(132, selectionGeneration, Endian.little);
    data.setUint32(140, shape, Endian.little);
    data.setUint32(144, color, Endian.little);
    data.setUint32(148, total, Endian.little);
    data.setUint32(240, 1, Endian.little);
    data.setUint32(244, 1, Endian.little);
    return RendererAttributeHeaderV1.decode(bytes);
  }

  factory RendererAttributeHeaderV1.decode(Uint8List raw) {
    if (raw.length < headerBytes || raw.length > pageBytes)
      throw FormatException('Attribute length.');
    final data = ByteData.sublistView(raw);
    if (data.getUint32(0, Endian.little) != _magic ||
        raw[4] != 1 ||
        raw[5] < select ||
        raw[5] > cancel ||
        data.getUint16(6, Endian.little) != headerBytes ||
        data.getUint32(240, Endian.little) != 1 ||
        data.getUint32(244, Endian.little) != 1 ||
        [
          for (var i = 232; i < 240; i++) raw[i],
          for (var i = 248; i < 256; i++) raw[i]
        ].any((v) => v != 0)) {
      throw FormatException('Attribute version/reserved fields.');
    }
    final header = RendererAttributeHeaderV1._(
        Uint8List.fromList(raw.sublist(0, headerBytes)));
    if (header.rendererGeneration < 0 ||
        header.selectionGeneration < 0 ||
        header.shape < patches ||
        header.shape > balls ||
        header.color < observations ||
        header.color > directionIdentity ||
        header.total > (header.shape == balls ? 256 : 4096) ||
        header.first + header.count > header.total) {
      throw FormatException('Attribute bounds.');
    }
    for (var field = 0; field < 10; field++) {
      if (data.getInt64(24 + field * 8, Endian.little) < 0) {
        throw FormatException('Style qualifier.');
      }
    }
    final target = data.getInt64(108, Endian.little);
    final direction = data.getInt32(116, Endian.little);
    if (data.getInt64(24, Endian.little) <= 0 ||
        data.getInt64(80, Endian.little) <= 0 ||
        raw[104] > 1 ||
        raw[105] != 0 ||
        data.getUint16(106, Endian.little) != 0 ||
        !((target == -1 && direction == -1) ||
            (target > 0 && direction >= 0 && direction < 24)) ||
        data.getUint32(120, Endian.little) > 100000 ||
        (header.operation == page &&
            (header.count > rowsPerPage ||
                (raw.length != headerBytes &&
                    raw.length != headerBytes + header.count * rowBytes))) ||
        (header.operation == select &&
            (header.count > idsPerPage ||
                (raw.length != headerBytes &&
                    raw.length != headerBytes + header.count * 8))) ||
        (header.operation >= commit && raw.length != headerBytes)) {
      throw FormatException('Attribute style or page extent.');
    }
    return header;
  }

  bool sameSelection(RendererAttributeHeaderV1 other) {
    for (var i = 8; i < 152; i++) {
      if (bytes[i] != other.bytes[i]) return false;
    }
    for (var i = 168; i < 200; i++) {
      if (bytes[i] != other.bytes[i]) return false;
    }
    for (var i = 240; i < 248; i++) {
      if (bytes[i] != other.bytes[i]) return false;
    }
    return true;
  }

  RendererAttributeHeaderV1 forPage(
      {required int operation,
      int index = 0,
      int pages = 0,
      int first = 0,
      int count = 0,
      Uint8List? attributeDigest}) {
    final result = Uint8List.fromList(bytes);
    result[5] = operation;
    final data = ByteData.sublistView(result);
    data.setUint32(152, index, Endian.little);
    data.setUint32(156, pages, Endian.little);
    data.setUint32(160, first, Endian.little);
    data.setUint32(164, count, Endian.little);
    if (attributeDigest != null) {
      if (attributeDigest.length != 32) throw ArgumentError('Digest length.');
      result.setRange(200, 232, attributeDigest);
    }
    return RendererAttributeHeaderV1.decode(result);
  }

  Uint8List encode([Uint8List? body]) {
    if ((body?.length ?? 0) > pageBytes - headerBytes)
      throw ArgumentError('Page length.');
    return Uint8List(headerBytes + (body?.length ?? 0))
      ..setRange(0, headerBytes, bytes)
      ..setRange(
          headerBytes, headerBytes + (body?.length ?? 0), body ?? Uint8List(0));
  }
}
