import CoreImage
import CoreVideo
import Foundation
import ImageIO

/// Isolated capture-format experiment. Never stages an image, admits a durable
/// photo or changes the configured encoding of ordinary capture.
final class TemporaryImageEncodingProbe {
  struct Failure: Error { let code: String; let message: String }

  private let queue = DispatchQueue(label: "capture3d.temporary-image-encoding", qos: .userInitiated)
  private let context = CIContext(options: [.cacheIntermediates: false])

  static func accepts(_ format: String) -> Bool { ["jpeg", "heif"].contains(format) }

  /// The supplied source remains owned by the caller until completion. One
  /// high-resolution frame is requested; failure never passes via video fallback.
  func capture(source: ARFrameSource, format: String, quality: Int, cameraAccess: StillCameraAccess? = nil,
               completion: @escaping (Result<[String: Any], Failure>) -> Void) {
    let started = DispatchTime.now().uptimeNanoseconds
    let receive: StillCameraAccess.FrameCompletion = { [self] frame, error in
      guard let frame = frame else {
        return completion(.failure(Failure(code: "ENCODING_CAPTURE_FAILED", message: "A high-resolution camera frame was unavailable")))
      }
      // Retain only the pixel buffer while encoding, never the ARFrame/source.
      let buffer = frame.capturedImage
      queue.async { [self] in
        do {
          var result = try encodeAndVerify(buffer, format: format, quality: quality)
          result["captureAndEncodeUs"] = Int((DispatchTime.now().uptimeNanoseconds - started) / 1_000)
          result["highResolution"] = true
          result["stillCaptureMode"] = "single"
          result["imageCount"] = 1
          result["requestedImageCount"] = 1
          completion(.success(result))
        } catch let failure as Failure { completion(.failure(failure)) }
        catch { completion(.failure(Failure(code: "ENCODING_TEST_FAILED", message: "Temporary encoding verification failed"))) }
      }
    }
    let request: (@escaping StillCameraAccess.FrameCompletion) -> Void = { completion in
      source.captureHighResolutionFrame(quality: .fast, completion: completion)
    }
    if let cameraAccess = cameraAccess { cameraAccess.capture(request, completion: receive) }
    else { request(receive) }
  }

  /// Encodes the actual camera buffer, flushes a uniquely owned temporary file,
  /// decodes it to establish type/dimensions, and removes it before returning.
  /// Also used by fixture tests; it does not touch any capture/session path.
  func encodeAndVerify(_ buffer: CVPixelBuffer, format: String, quality: Int,
                       directory: URL = FileManager.default.temporaryDirectory) throws -> [String: Any] {
    guard Self.accepts(format), (10...100).contains(quality) else {
      throw Failure(code: "CONFIG_INVALID", message: "Choose JPEG or HEIF and quality 10-100")
    }
    guard let space = CGColorSpace(name: CGColorSpace.sRGB) else {
      throw Failure(code: "ENCODING_TEST_FAILED", message: "sRGB color space unavailable")
    }
    let width = CVPixelBufferGetWidth(buffer), height = CVPixelBufferGetHeight(buffer)
    let image = CIImage(cvPixelBuffer: buffer, options: [.properties: [kCGImagePropertyOrientation as String: 1]])
    let options: [CIImageRepresentationOption: Any] = [
      CIImageRepresentationOption(rawValue: kCGImageDestinationLossyCompressionQuality as String): Double(quality) / 100,
    ]
    let started = DispatchTime.now().uptimeNanoseconds
    let bytes = format == "heif"
      ? context.heifRepresentation(of: image, format: .RGBA8, colorSpace: space, options: options)
      : context.jpegRepresentation(of: image, colorSpace: space, options: options)
    let encodeUs = Int((DispatchTime.now().uptimeNanoseconds - started) / 1_000)
    guard let bytes = bytes, !bytes.isEmpty else {
      throw Failure(code: "ENCODING_UNAVAILABLE", message: "The device could not encode this camera buffer")
    }
    var result = try verifyEncoded(bytes, format: format, width: width, height: height, directory: directory)
    result["encodeUs"] = encodeUs
    result["quality"] = quality
    return result
  }

  /// Verifies all outputs of the production manual-shot path. A partial burst
  /// or video fallback is measurable failure, never certified as the full mode.
  func verifyShot(_ shot: CapturedShot, mode: StillCaptureMode, requestedImageCount: Int,
                  quality: Int, encodeUs: Int, startedAt: UInt64,
                  completion: @escaping (Result<[String: Any], Failure>) -> Void) {
    queue.async { [self] in
      do {
        guard shot.images.count == requestedImageCount else {
          throw Failure(code: "ENCODING_INCOMPLETE_BURST", message: "The camera returned fewer images than the requested burst")
        }
        guard shot.images.allSatisfy({ $0.metadata.highResolution }) else {
          throw Failure(code: "ENCODING_CAPTURE_FAILED", message: "A high-resolution frame was unavailable; video fallback cannot certify this profile")
        }
        guard let first = shot.images.first,
              shot.images.allSatisfy({ $0.metadata.width == first.metadata.width && $0.metadata.height == first.metadata.height }) else {
          throw Failure(code: "ENCODING_GEOMETRY_MISMATCH", message: "Every image of a burst must have the same photo dimensions")
        }
        var images = [[String: Any]]()
        for image in shot.images {
          images.append(try verifyEncoded(Data(image.jpeg), format: "jpeg",
            width: image.metadata.width, height: image.metadata.height))
        }
        guard var result = images.first else {
          throw Failure(code: "ENCODING_CAPTURE_FAILED", message: "No image was captured")
        }
        result["byteLength"] = shot.images.reduce(0) { $0 + $1.jpeg.count }
        result["encodeUs"] = encodeUs
        result["captureAndEncodeUs"] = Int((DispatchTime.now().uptimeNanoseconds - startedAt) / 1_000)
        result["quality"] = quality
        result["highResolution"] = true
        result["stillCaptureMode"] = mode.rawValue
        result["imageCount"] = shot.images.count
        result["requestedImageCount"] = requestedImageCount
        result["images"] = images
        completion(.success(result))
      } catch let failure as Failure { completion(.failure(failure)) }
      catch { completion(.failure(Failure(code: "ENCODING_TEST_FAILED", message: "Temporary shot verification failed"))) }
    }
  }

  private func verifyEncoded(_ bytes: Data, format: String, width: Int, height: Int,
                             directory: URL = FileManager.default.temporaryDirectory) throws -> [String: Any] {
    let ext = format == "heif" ? "heic" : "jpg"
    let file = directory.appendingPathComponent("capture-encoding-\(UUID().uuidString).\(ext)")
    var result: [String: Any] = [:]
    do {
      try bytes.write(to: file, options: .withoutOverwriting)
      let handle = try FileHandle(forWritingTo: file)
      do { try handle.synchronize(); try handle.close() }
      catch { try? handle.close(); throw error }
      guard let source = CGImageSourceCreateWithURL(file as CFURL, nil),
            let type = CGImageSourceGetType(source) as String?,
            let decoded = CGImageSourceCreateImageAtIndex(source, 0, [
              kCGImageSourceShouldCache: true, kCGImageSourceShouldCacheImmediately: true,
            ] as CFDictionary),
            decoded.width == width, decoded.height == height,
            type == (format == "heif" ? "public.heic" : "public.jpeg") else {
        throw Failure(code: "ENCODING_VERIFY_FAILED", message: "The encoded file did not decode with the requested format and dimensions")
      }
      result = ["requestedFormat": format, "actualFormat": format, "width": decoded.width,
                "height": decoded.height, "byteLength": bytes.count]
    } catch {
      if FileManager.default.fileExists(atPath: file.path) {
        do { try FileManager.default.removeItem(at: file) }
        catch { throw Failure(code: "ENCODING_CLEANUP_FAILED", message: "The owned temporary test file could not be removed") }
      }
      throw error
    }
    do { try FileManager.default.removeItem(at: file) }
    catch { throw Failure(code: "ENCODING_CLEANUP_FAILED", message: "The owned temporary test file could not be removed") }
    result["temporaryFileRemoved"] = true
    return result
  }
}
