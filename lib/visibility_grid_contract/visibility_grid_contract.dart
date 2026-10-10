/// Platform boundary contract of the visibility grid: ingest messages, photo
/// saved events, marker detections, renderer deltas, display modes, colour
/// tables, shell control, ingest recordings, channel names and native status
/// codes. Byte offsets are normative and mirrored in Kotlin
/// (`visibilitycontract/`) and C (`native/**/include/`).
library;

export 'binary_support.dart';
export 'display_mode.dart';
export 'ingest_message.dart';
export 'ingest_recording.dart';
export 'marker_detection.dart';
export 'native_status_code.dart';
export 'photo_saved.dart';
export 'platform_shell_messages.dart';
export 'renderer_delta.dart';
