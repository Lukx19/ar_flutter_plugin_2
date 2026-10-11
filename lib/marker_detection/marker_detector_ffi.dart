import 'dart:ffi';
import 'dart:io';
import 'dart:typed_data';

import '../visibility_grid_contract/visibility_grid_contract.dart';

/// Status codes of the marker detector C ABI (`marker_detector.h`).
abstract final class MarkerDetectorStatus {
  static const int ok = 0;
  static const int argument = -1;
  static const int unavailable = -2;
  static const int internal = -3;
}

/// Dictionaries of the detector. 0 is the contract value of the detection
/// record; the others are proposed extensions of its dictionary byte.
abstract final class MarkerDetectorDictionary {
  static const int arucoMip36h12 = MarkerDictionary.arucoMip36h12;
  static const int aprilTag36h11 = 1;
  static const int aruco4x4_50 = 2;
  static const int aruco6x6_250 = 3;
}

abstract final class MarkerPoseMethod {
  static const int ippeSquare = 0;
  static const int sqpnp = 1;
}

abstract final class MarkerCornerRefinement {
  static const int none = 0;
  static const int cornerSubPix = 1;
  static const int edgeLines = 2;
}

typedef _CreateNative = Pointer<Void> Function(Int32);
typedef _Create = Pointer<Void> Function(int);
typedef _DestroyNative = Void Function(Pointer<Void>);
typedef _Destroy = void Function(Pointer<Void>);
typedef _IntNative = Int32 Function();
typedef _Int = int Function();
typedef _SetCameraNative = Int32 Function(
    Pointer<Void>, Double, Double, Double, Double, Pointer<Double>);
typedef _SetCamera = int Function(
    Pointer<Void>, double, double, double, double, Pointer<Double>);
typedef _SetDistortionNative = Int32 Function(
    Pointer<Void>, Double, Double, Double, Double, Double);
typedef _SetDistortion = int Function(
    Pointer<Void>, double, double, double, double, double);
typedef _ScratchNative = Pointer<Uint8> Function(Pointer<Void>, Int64);
typedef _Scratch = Pointer<Uint8> Function(Pointer<Void>, int);
typedef _SetSizeNative = Int32 Function(Pointer<Void>, Double);
typedef _SetSize = int Function(Pointer<Void>, double);
typedef _SetSizeForIdNative = Int32 Function(Pointer<Void>, Int32, Double);
typedef _SetSizeForId = int Function(Pointer<Void>, int, double);
typedef _HandleIntNative = Int32 Function(Pointer<Void>, Int32);
typedef _HandleInt = int Function(Pointer<Void>, int);
typedef _HandleNative = Int32 Function(Pointer<Void>);
typedef _Handle = int Function(Pointer<Void>);
typedef _DetectNative = Int32 Function(
    Pointer<Void>, Pointer<Uint8>, Int32, Int32, Int32);
typedef _Detect = int Function(Pointer<Void>, Pointer<Uint8>, int, int, int);
typedef _DetectTiesNative = Int32 Function(
    Pointer<Void>, Pointer<Uint8>, Int32, Int32, Int32, Int32, Int32);
typedef _DetectTies = int Function(
    Pointer<Void>, Pointer<Uint8>, int, int, int, int, int);
typedef _BytesNative = Pointer<Uint8> Function(Pointer<Void>);
typedef _DoublesNative = Pointer<Double> Function(Pointer<Void>);
typedef _SolveNative = Int32 Function(Pointer<Void>, Int32, Pointer<Double>,
    Pointer<Float>, Int32, Pointer<Double>);
typedef _Solve = int Function(
    Pointer<Void>, int, Pointer<Double>, Pointer<Float>, int, Pointer<Double>);
typedef _RefineNative = Int32 Function(
  Pointer<Uint8>,
  Int32,
  Int32,
  Int32,
  Pointer<Float>,
  Int32,
  Pointer<Double>,
  Pointer<Double>,
  Pointer<Float>,
);
typedef _Refine = int Function(
  Pointer<Uint8>,
  int,
  int,
  int,
  Pointer<Float>,
  int,
  Pointer<Double>,
  Pointer<Double>,
  Pointer<Float>,
);
typedef _CentreNative = Int32 Function(Pointer<Float>, Pointer<Float>);
typedef _Centre = int Function(Pointer<Float>, Pointer<Float>);
typedef _AngleNative = Float Function(Pointer<Double>, Pointer<Double>);
typedef _Angle = double Function(Pointer<Double>, Pointer<Double>);
typedef _EncodeNative = Void Function(
  Uint16,
  Uint8,
  Uint8,
  Pointer<Float>,
  Pointer<Double>,
  Pointer<Double>,
  Float,
  Float,
  Float,
  Pointer<Uint8>,
);
typedef _Encode = void Function(
  int,
  int,
  int,
  Pointer<Float>,
  Pointer<Double>,
  Pointer<Double>,
  double,
  double,
  double,
  Pointer<Uint8>,
);

/// Function table of the shared marker detector library.
final class MarkerDetectorLibrary {
  MarkerDetectorLibrary(DynamicLibrary lib)
      : abiVersion = lib.lookupFunction<_IntNative, _Int>('c3md_abi_version'),
        detectorAvailable = lib.lookupFunction<_IntNative, _Int>(
          'c3md_detector_available',
        ),
        create = lib.lookupFunction<_CreateNative, _Create>('c3md_create'),
        destroy = lib.lookupFunction<_DestroyNative, _Destroy>('c3md_destroy'),
        setCamera = lib.lookupFunction<_SetCameraNative, _SetCamera>(
          'c3md_set_camera',
        ),
        setDistortion =
            lib.lookupFunction<_SetDistortionNative, _SetDistortion>(
          'c3md_set_distortion',
        ),
        scratch = lib.lookupFunction<_ScratchNative, _Scratch>('c3md_scratch'),
        setMarkerSize = lib.lookupFunction<_SetSizeNative, _SetSize>(
          'c3md_set_marker_size',
        ),
        setMarkerSizeForId =
            lib.lookupFunction<_SetSizeForIdNative, _SetSizeForId>(
          'c3md_set_marker_size_for_id',
        ),
        setPoseMethod = lib.lookupFunction<_HandleIntNative, _HandleInt>(
          'c3md_set_pose_method',
        ),
        detect = lib.lookupFunction<_DetectNative, _Detect>('c3md_detect'),
        estimatePose = lib.lookupFunction<_HandleNative, _Handle>(
          'c3md_estimate_pose',
        ),
        records =
            lib.lookupFunction<_BytesNative, _BytesNative>('c3md_records'),
        recordCount = lib.lookupFunction<_HandleNative, _Handle>(
          'c3md_record_count',
        ),
        solvePoints = lib.lookupFunction<_SolveNative, _Solve>(
          'c3md_solve_points',
        ),
        detectTiePoints = lib.lookupFunction<_DetectTiesNative, _DetectTies>(
          'c3md_detect_tiepoints',
        ),
        tiePoints = lib.lookupFunction<_BytesNative, _BytesNative>(
          'c3md_tiepoints',
        ),
        timings = lib.lookupFunction<_DoublesNative, _DoublesNative>(
          'c3md_timings',
        ),
        refineEdges = lib.lookupFunction<_RefineNative, _Refine>(
          'c3md_refine_edges',
        ),
        diagonalCentre = lib.lookupFunction<_CentreNative, _Centre>(
          'c3md_diagonal_centre',
        ),
        viewingAngleDeg = lib.lookupFunction<_AngleNative, _Angle>(
          'c3md_viewing_angle_deg',
        ),
        encodeRecord = lib.lookupFunction<_EncodeNative, _Encode>(
          'c3md_encode_record',
        );

  /// Opens the library of this platform: `libcapture3d_marker_detector.so`
  /// on Android, the process image on iOS (statically linked), or [path].
  factory MarkerDetectorLibrary.open([String? path]) {
    if (path != null) {
      return MarkerDetectorLibrary(DynamicLibrary.open(path));
    }
    if (Platform.isIOS || Platform.isMacOS) {
      return MarkerDetectorLibrary(DynamicLibrary.process());
    }
    if (Platform.isWindows) {
      return MarkerDetectorLibrary(
        DynamicLibrary.open('capture3d_marker_detector.dll'),
      );
    }
    return MarkerDetectorLibrary(
      DynamicLibrary.open('libcapture3d_marker_detector.so'),
    );
  }

  static const int maxMarkers = 128;
  static const int tiePointBytes = 64;

  final _Int abiVersion;
  final _Int detectorAvailable;
  final _Create create;
  final _Destroy destroy;
  final _SetCamera setCamera;
  final _SetDistortion setDistortion;
  final _Scratch scratch;
  final _SetSize setMarkerSize;
  final _SetSizeForId setMarkerSizeForId;
  final _HandleInt setPoseMethod;
  final _Detect detect;
  final _Handle estimatePose;
  final _BytesNative records;
  final _Handle recordCount;
  final _Solve solvePoints;
  final _DetectTies detectTiePoints;
  final _BytesNative tiePoints;
  final _DoublesNative timings;
  final _Refine refineEdges;
  final _Centre diagonalCentre;
  final _Angle viewingAngleDeg;
  final _Encode encodeRecord;
}

/// One native detector with views over its native buffers, created once.
/// Calls allocate nothing in Dart: grey images go through the detector's
/// scratch ([imageBuffer]), results are read through [records] and
/// [tiePoints]. Not thread-safe; use one per isolate.
final class MarkerDetector {
  MarkerDetector._(this.library, this._handle)
      : records = ByteData.sublistView(
          library.records(_handle).asTypedList(
              MarkerDetectorLibrary.maxMarkers * MarkerDetectionLayout.bytes),
        ),
        tiePoints = ByteData.sublistView(
          library.tiePoints(_handle).asTypedList(
                MarkerDetectorLibrary.maxMarkers *
                    MarkerDetectorLibrary.tiePointBytes,
              ),
        ),
        timings = library.timings(_handle).asTypedList(3);

  /// Creates a detector for [dictionary]; null for an unknown dictionary.
  static MarkerDetector? create(MarkerDetectorLibrary library, int dictionary) {
    final handle = library.create(dictionary);
    return handle == nullptr ? null : MarkerDetector._(library, handle);
  }

  final MarkerDetectorLibrary library;
  final Pointer<Void> _handle;
  bool _disposed = false;

  /// 64 B contract records of the last detection (see
  /// `MarkerDetectionRecordView`); [recordCount] are valid.
  final ByteData records;

  /// 64 B native tie point records of the last tie-point detection.
  final ByteData tiePoints;

  /// Last detect, pose and refine times in ms.
  final Float64List timings;

  bool get available => library.detectorAvailable() == 1;
  int get recordCount => library.recordCount(_handle);

  int setCamera(
    double fx,
    double fy,
    double cx,
    double cy, {
    List<double>? distortion,
  }) {
    final status = library.setCamera(_handle, fx, fy, cx, cy, nullptr);
    if (status != MarkerDetectorStatus.ok || distortion == null) {
      return status;
    }
    return library.setDistortion(
      _handle,
      distortion[0],
      distortion[1],
      distortion[2],
      distortion[3],
      distortion[4],
    );
  }

  int setMarkerSize(double metres) => library.setMarkerSize(_handle, metres);
  int setMarkerSizeForId(int id, double metres) =>
      library.setMarkerSizeForId(_handle, id, metres);
  int setPoseMethod(int method) => library.setPoseMethod(_handle, method);

  Pointer<Uint8> _image = nullptr;
  int _imageBytes = 0;

  /// Native buffer of at least [bytes] for a grey image; the view stays
  /// valid until the next call that needs more scratch.
  Uint8List imageBuffer(int bytes) {
    if (bytes > _imageBytes || _image == nullptr) {
      _image = library.scratch(_handle, bytes);
      if (_image == nullptr) {
        throw StateError('marker detector scratch of $bytes B unavailable');
      }
      _imageBytes = bytes;
    }
    return _image.asTypedList(bytes);
  }

  /// Native address of the scratch behind [imageBuffer] (for calls of the
  /// geometry helpers through [library]).
  Pointer<Uint8> get imagePointer => _image;

  /// Detects markers on the grey image in [imageBuffer]; returns the count
  /// or a negative [MarkerDetectorStatus].
  int detect(int width, int height, int stride) =>
      library.detect(_handle, _image, width, height, stride);

  /// Poses, viewing angles and quality flags of the last detection.
  int estimatePose() => library.estimatePose(_handle);

  /// Full-resolution tie points of the grey image in [imageBuffer].
  int detectTiePoints(
    int width,
    int height,
    int stride, {
    int searchDownscale = 1,
    int refinement = MarkerCornerRefinement.edgeLines,
  }) =>
      library.detectTiePoints(
        _handle,
        _image,
        width,
        height,
        stride,
        searchDownscale,
        refinement,
      );

  void dispose() {
    if (_disposed) {
      return;
    }
    _disposed = true;
    library.destroy(_handle);
  }
}
