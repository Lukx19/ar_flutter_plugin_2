import CoreGraphics
import Foundation
import ImageIO

/// JPEG sharpness with the Android `JpegBlurAnalyzer` definition
/// (`jpegLaplacianVarianceV1`): the JPEG is decoded at a power-of-two sample
/// so its long edge is at most 1024 px, converted to integer BT.709 luma
/// `(R*2126 + G*7152 + B*722) / 10000`, and the variance of the 4-neighbour
/// Laplacian is taken inside a 5 % border. The row walk, sampling step,
/// border and accumulation order are a line-by-line port, so equal luma rows
/// give the same score as Android. The sampled decode uses ImageIO, whose
/// down-sampling filter is not bit-identical to Android's decoder.
enum LaplacianSharpness {
  static let maxAnalysisDimension = 1024
  static let productionThreshold = 110.0
  static let algorithm = "jpegLaplacianVarianceV1"

  struct Result: Equatable {
    var score: Double
    var sampledWidth: Int
    var sampledHeight: Int
  }

  /// Power of two so that `longEdge / sample <= 1024` (Android `calculateInSampleSize`).
  static func sampleSize(width: Int, height: Int) -> Int {
    precondition(width > 0 && height > 0)
    let longEdge = max(width, height)
    var sample = 1
    while longEdge / sample > maxAnalysisDimension { sample *= 2 }
    return sample
  }

  /// Score of an encoded JPEG; nil when it cannot be decoded.
  static func analyze(jpeg: Data) -> Result? {
    guard let source = CGImageSourceCreateWithData(jpeg as CFData, nil),
          let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
          let width = (properties[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue,
          let height = (properties[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue,
          width > 0, height > 0 else { return nil }
    let sample = sampleSize(width: width, height: height)
    let longEdge = (max(width, height) + sample - 1) / sample
    let options: [CFString: Any] = [
      kCGImageSourceCreateThumbnailFromImageAlways: true,
      kCGImageSourceThumbnailMaxPixelSize: longEdge,
      kCGImageSourceCreateThumbnailWithTransform: false,
      kCGImageSourceShouldCacheImmediately: true,
    ]
    guard let image = sample == 1
      ? CGImageSourceCreateImageAtIndex(source, 0, nil)
      : CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
    return analyze(image: image)
  }

  static func analyze(image: CGImage) -> Result? {
    let width = image.width, height = image.height
    guard width >= 3, height >= 3, width <= maxAnalysisDimension + 1, height <= maxAnalysisDimension + 1,
          let space = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
    var rgba = [UInt8](repeating: 0, count: width * height * 4)
    let drawn: Bool = rgba.withUnsafeMutableBytes { raw in
      guard let context = CGContext(
        data: raw.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4, space: space,
        bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue) else { return false }
      context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
      return true
    }
    guard drawn else { return nil }
    return analyzeSampledRows(width: width, height: height) { y, row in
      let base = y * width * 4
      for x in 0..<width {
        let r = Int(rgba[base + 4 * x]), g = Int(rgba[base + 4 * x + 1]), b = Int(rgba[base + 4 * x + 2])
        row[x] = UInt8((r * 2126 + g * 7152 + b * 722) / 10000)
      }
    }
  }

  /// Android `analyzeSampledRows`: rows are loaded on demand into three buffers.
  static func analyzeSampledRows(width: Int, height: Int, loadRow: (Int, inout [UInt8]) -> Void) -> Result? {
    guard width > 2, height > 2 else { return nil }
    let step = max(width, height) <= maxAnalysisDimension ? 1 : (max(width, height) + maxAnalysisDimension - 1) / maxAnalysisDimension
    let sampledWidth = width / step
    let sampledHeight = height / step
    guard sampledWidth > 2, sampledHeight > 2 else { return nil }
    let borderX = min(Int(Double(sampledWidth) * 0.05), (sampledWidth - 3) / 2)
    let borderY = min(Int(Double(sampledHeight) * 0.05), (sampledHeight - 3) / 2)
    let startX = borderX + 1, endX = sampledWidth - borderX - 1
    let startY = borderY + 1, endY = sampledHeight - borderY - 1
    guard startX < endX, startY < endY else { return nil }
    var top = [UInt8](repeating: 0, count: width)
    var current = [UInt8](repeating: 0, count: width)
    var bottom = [UInt8](repeating: 0, count: width)
    loadRow((startY - 1) * step, &top)
    loadRow(startY * step, &current)
    var sum = 0.0
    var sumSquares = 0.0
    var count = 0
    for y in startY..<endY {
      loadRow((y + 1) * step, &bottom)
      for x in startX..<endX {
        let sx = x * step
        let laplacian = Double(-4 * Int(current[sx]) + Int(current[sx - step]) + Int(current[sx + step]) + Int(top[sx]) + Int(bottom[sx]))
        sum += laplacian
        sumSquares += laplacian * laplacian
        count += 1
      }
      let reusable = top
      top = current
      current = bottom
      bottom = reusable
    }
    let mean = sum / Double(count)
    return Result(score: max(sumSquares / Double(count) - mean * mean, 0), sampledWidth: sampledWidth, sampledHeight: sampledHeight)
  }
}
