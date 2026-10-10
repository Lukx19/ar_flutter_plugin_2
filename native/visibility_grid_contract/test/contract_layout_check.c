/*
 * Compiles the contract headers so their static size and offset assertions
 * run; also reads one golden when a path is given.
 *
 *   cc -std=c11 -I../include -I../../marker_detector/include contract_layout_check.c
 *   ./a.out ../../../test_assets/visibility_grid/ingest_markers.bin
 */
#include <stdio.h>
#include <string.h>

#include "ingest_message_layout.h"
#include "marker_detection_record.h"

int main(int argc, char **argv) {
  if (argc < 2) return 0;
  unsigned char buffer[1024];
  FILE *file = fopen(argv[1], "rb");
  if (!file) return 2;
  size_t length = fread(buffer, 1, sizeof buffer, file);
  fclose(file);
  C3IngestHeader header;
  if (length < sizeof header) return 3;
  memcpy(&header, buffer, sizeof header);
  if (header.magic != C3_INGEST_MAGIC || header.version != C3_INGEST_VERSION) return 4;
  if (header.kind != C3_INGEST_KIND_MARKER_DETECTIONS) return 0;
  C3MarkerDetectionsPrefix prefix;
  memcpy(&prefix, buffer + sizeof header, sizeof prefix);
  C3MarkerDetectionRecord record;
  memcpy(&record, buffer + sizeof header + sizeof prefix + sizeof record, sizeof record);
  if (prefix.count != 2 || record.marker_id != 13 ||
      record.flags != C3_MARKER_FLAG_REJECTED_BLUR || record.sharpness != 200) {
    return 5;
  }
  printf("marker golden ok: %u records, second id %u\n", prefix.count, record.marker_id);
  return 0;
}
