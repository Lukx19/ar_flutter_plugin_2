import 'dart:typed_data';

/// Fixed byte width of [ARCoverageRendererStyleRowV1].
const int coverageRendererStyleRowV1Bytes = 16;

enum ARCoverageRendererSemantic { confirmed, ambiguous, suppressedDebug }

enum ARCoverageRendererCoverage { uncovered, partial, complete }

enum ARCoverageRendererPalette {
  uniform,
  coverage,
  normal,
  occupancy,
  lineage,
  age,
  sourceHealth,
  residency,
  direction,
}

enum ARCoverageRendererCut {
  exactCurrent,
  staleDisplay,
  lowerBound,
  coveragePending,
  indeterminateHistory,
  unavailable,
}

enum ARCoverageRendererResidency { activeL0, warmL1, coldL2 }

enum ARCoverageRendererTarget { none, primary, halo }

enum ARCoverageRendererGlyph { none, normal, desiredDirection, viewRose }

enum ARCoverageRendererAge { fresh, recent, aging, old }

enum ARCoverageRendererSourceHealth {
  healthy,
  featureOnly,
  transientUnavailable,
  failed,
  unsupported,
}

/// Fixed-width, disposable renderer projection of one semantic/style cut.
final class ARCoverageRendererStyleRowV1 {
  ARCoverageRendererStyleRowV1({
    this.semanticGeneration = 0,
    this.styleGeneration = 0,
    this.semantic = ARCoverageRendererSemantic.confirmed,
    this.coverage = ARCoverageRendererCoverage.uncovered,
    this.palette = ARCoverageRendererPalette.coverage,
    this.cut = ARCoverageRendererCut.exactCurrent,
    this.residency = ARCoverageRendererResidency.activeL0,
    this.target = ARCoverageRendererTarget.none,
    this.directionBin = 0xff,
    this.glyph = ARCoverageRendererGlyph.none,
    this.lineageCount = 0,
    this.age = ARCoverageRendererAge.fresh,
    this.sourceHealth = ARCoverageRendererSourceHealth.healthy,
  }) {
    if (semanticGeneration < 0 ||
        semanticGeneration > 0xffffffff ||
        styleGeneration < 0 ||
        styleGeneration > 0xffffffff ||
        lineageCount < 0 ||
        lineageCount > 0xffff ||
        !(directionBin == 0xff || directionBin >= 0 && directionBin <= 23) ||
        ((glyph == ARCoverageRendererGlyph.none ||
                glyph == ARCoverageRendererGlyph.normal) !=
            (directionBin == 0xff))) {
      throw ArgumentError('Invalid coverage renderer style row.');
    }
  }

  final int semanticGeneration;
  final int styleGeneration;
  final ARCoverageRendererSemantic semantic;
  final ARCoverageRendererCoverage coverage;
  final ARCoverageRendererPalette palette;
  final ARCoverageRendererCut cut;
  final ARCoverageRendererResidency residency;
  final ARCoverageRendererTarget target;
  final int directionBin;
  final ARCoverageRendererGlyph glyph;
  final int lineageCount;
  final ARCoverageRendererAge age;
  final ARCoverageRendererSourceHealth sourceHealth;

  /// True when all rows name one committed semantic/style cut.
  static bool hasCoherentGenerations(
    Iterable<ARCoverageRendererStyleRowV1> rows,
  ) {
    int? semantic;
    int? style;
    for (final row in rows) {
      semantic ??= row.semanticGeneration;
      style ??= row.styleGeneration;
      if (row.semanticGeneration != semantic || row.styleGeneration != style) {
        return false;
      }
    }
    return true;
  }

  Uint8List encode() {
    final bytes = Uint8List(coverageRendererStyleRowV1Bytes);
    final data = ByteData.sublistView(bytes);
    bytes[0] = 1;
    bytes[1] = semantic.index |
        coverage.index << 2 |
        residency.index << 4 |
        target.index << 6;
    bytes[2] = palette.index | cut.index << 4;
    bytes[3] = glyph.index | age.index << 2 | sourceHealth.index << 4;
    bytes[4] = directionBin;
    data.setUint16(6, lineageCount, Endian.little);
    data.setUint32(8, semanticGeneration, Endian.little);
    data.setUint32(12, styleGeneration, Endian.little);
    return bytes;
  }

  static ARCoverageRendererStyleRowV1 decode(
    Uint8List bytes, [
    int offset = 0,
  ]) {
    if (offset < 0 || bytes.length - offset < coverageRendererStyleRowV1Bytes) {
      throw const FormatException('Truncated coverage renderer style row.');
    }
    final row = Uint8List.sublistView(
      bytes,
      offset,
      offset + coverageRendererStyleRowV1Bytes,
    );
    if (row[0] != 1 ||
        row[2] & 0x80 != 0 ||
        row[3] & 0x80 != 0 ||
        row[5] != 0) {
      throw const FormatException('Reserved coverage renderer style value.');
    }

    T value<T extends Enum>(List<T> values, int index) {
      if (index < 0 || index >= values.length) {
        throw const FormatException('Reserved coverage renderer enum code.');
      }
      return values[index];
    }

    final semanticBits = row[1];
    final paletteCutBits = row[2];
    final glyphAgeHealthBits = row[3];
    final data = ByteData.sublistView(row);
    try {
      return ARCoverageRendererStyleRowV1(
        semanticGeneration: data.getUint32(8, Endian.little),
        styleGeneration: data.getUint32(12, Endian.little),
        semantic: value(ARCoverageRendererSemantic.values, semanticBits & 0x3),
        coverage:
            value(ARCoverageRendererCoverage.values, (semanticBits >> 2) & 0x3),
        palette: value(ARCoverageRendererPalette.values, paletteCutBits & 0xf),
        cut: value(ARCoverageRendererCut.values, (paletteCutBits >> 4) & 0x7),
        residency: value(
          ARCoverageRendererResidency.values,
          (semanticBits >> 4) & 0x3,
        ),
        target: value(
          ARCoverageRendererTarget.values,
          (semanticBits >> 6) & 0x3,
        ),
        directionBin: row[4],
        glyph: value(ARCoverageRendererGlyph.values, glyphAgeHealthBits & 0x3),
        lineageCount: data.getUint16(6, Endian.little),
        age: value(
          ARCoverageRendererAge.values,
          (glyphAgeHealthBits >> 2) & 0x3,
        ),
        sourceHealth: value(
          ARCoverageRendererSourceHealth.values,
          (glyphAgeHealthBits >> 4) & 0x7,
        ),
      );
    } on ArgumentError catch (error) {
      throw FormatException('Invalid coverage renderer style row.', error);
    }
  }

  @override
  bool operator ==(Object other) =>
      other is ARCoverageRendererStyleRowV1 &&
      semanticGeneration == other.semanticGeneration &&
      styleGeneration == other.styleGeneration &&
      semantic == other.semantic &&
      coverage == other.coverage &&
      palette == other.palette &&
      cut == other.cut &&
      residency == other.residency &&
      target == other.target &&
      directionBin == other.directionBin &&
      glyph == other.glyph &&
      lineageCount == other.lineageCount &&
      age == other.age &&
      sourceHealth == other.sourceHealth;

  @override
  int get hashCode => Object.hash(
        semanticGeneration,
        styleGeneration,
        semantic,
        coverage,
        palette,
        cut,
        residency,
        target,
        directionBin,
        glyph,
        lineageCount,
        age,
        sourceHealth,
      );
}
