import Foundation
import Flutter
import ImageIO
import UniformTypeIdentifiers

/// Where the native capture stores live: the app's Application Support folder,
/// the iOS counterpart of Android `Context.filesDir` (app private, not shown
/// in Files). The Dart side never reads these paths; it receives terminal ids
/// and preview copies only.
enum NativeCaptureStorePaths {
  static func applicationFiles() -> URL {
    let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    return base
  }
  /// `capture-v2-native` (lease root; `store/` and `budget/` below it).
  static func nativeRoot(_ appFiles: URL = applicationFiles()) -> URL { appFiles.appendingPathComponent("capture-v2-native", isDirectory: true) }
  static func store(_ appFiles: URL = applicationFiles()) -> URL { nativeRoot(appFiles).appendingPathComponent("store", isDirectory: true) }
  static func budget(_ appFiles: URL = applicationFiles()) -> URL { nativeRoot(appFiles).appendingPathComponent("budget", isDirectory: true) }
  static func previews(_ appFiles: URL = applicationFiles()) -> URL { appFiles.appendingPathComponent("capture-v2-previews", isDirectory: true) }
}

/// Display limits of preview copies (Android `CaptureThumbnailLimits`).
enum CapturePreviewThumbnail {
  static let maxFileBytes = 256 * 1024

  static func valid(_ url: URL, edge: Int) -> Bool {
    guard edge == 512 || edge == 1024,
          let size = (try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? NSNumber,
          (1...maxFileBytes * (edge / 512)).contains(size.intValue),
          let source = CGImageSourceCreateWithURL(url as CFURL, nil),
          CGImageSourceGetType(source) as String? == UTType.jpeg.identifier,
          let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
          let width = (properties[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue,
          let height = (properties[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue else { return false }
    return (1...edge).contains(width) && (1...edge).contains(height)
  }

  /// Oriented JPEG preview with its long edge at most [edge] (quality 78 at
  /// 512 px, 85 at 1024 px).
  static func write(source: URL, target: URL, edge: Int, cancelled: () -> Bool) -> Bool {
    guard !cancelled(), let imageSource = CGImageSourceCreateWithURL(source as CFURL, nil) else { return false }
    let options: [CFString: Any] = [
      kCGImageSourceCreateThumbnailFromImageAlways: true,
      kCGImageSourceThumbnailMaxPixelSize: edge,
      kCGImageSourceCreateThumbnailWithTransform: true,
    ]
    guard let image = CGImageSourceCreateThumbnailAtIndex(imageSource, 0, options as CFDictionary), !cancelled(),
          let destination = CGImageDestinationCreateWithURL(target as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else { return false }
    let properties = CGImageSourceCopyPropertiesAtIndex(imageSource, 0, nil) as? [CFString: Any]
    let width = (properties?[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue ?? 0
    let height = (properties?[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue ?? 0
    let orientation = (properties?[kCGImagePropertyOrientation] as? NSNumber)?.intValue ?? 1
    let description = "guidance_preview_v1:\(width):\(height):\(orientation)"
    CGImageDestinationAddImage(destination, image, [
      kCGImageDestinationLossyCompressionQuality: edge == 512 ? 0.78 : 0.85,
      kCGImagePropertyTIFFDictionary: [kCGImagePropertyTIFFImageDescription: description],
    ] as CFDictionary)
    guard CGImageDestinationFinalize(destination), !cancelled() else { return false }
    return valid(target, edge: edge)
  }
}

/// Read-only access to committed JPEGs of the durable store for History and
/// detail screens (Android `NativeCapturePreviewReaderV2`): never takes the
/// store lease; validates the selected root chain, the bound committed receipt
/// and the JPEG hash before writing a display copy.
final class NativeCapturePreviewReader {
  private let storeRoot: URL
  private let lock = NSLock()
  static let maxRootDepth = 2
  static let maxReceiptBytes = 64 * 1024

  init(storeRoot: URL) { self.storeRoot = storeRoot }

  func originalFormat(manifestId: String, captureId: String) -> String? {
    lock.lock(); defer { lock.unlock() }
    let sessions = storeRoot.appendingPathComponent("sessions", isDirectory: true)
    guard let entries = try? FileManager.default.contentsOfDirectory(atPath: sessions.path) else { return nil }
    for name in entries.sorted() {
      let session = sessions.appendingPathComponent(name, isDirectory: true)
      guard Self.isPlainDirectory(session), !FileManager.default.fileExists(atPath: session.appendingPathComponent("tombstone").path),
            let image = committedImage(session, manifestId, captureId, { false }) else { continue }
      return image.source.lastPathComponent.hasPrefix("heic.") ? "heic" : "jpeg"
    }
    return nil
  }

  func materialize(manifestId: String, captureId: String, target: URL, edge: Int, cancelled: () -> Bool) -> Bool {
    lock.lock(); defer { lock.unlock() }
    guard edge == 0 || edge == 512 || edge == 1024, DurableStoreEncoding.isHash(manifestId), DurableStoreEncoding.isHash(captureId) else { return false }
    let sessions = storeRoot.appendingPathComponent("sessions", isDirectory: true)
    guard let entries = try? FileManager.default.contentsOfDirectory(atPath: sessions.path) else { return false }
    for name in entries.sorted() {
      if cancelled() { return false }
      let session = sessions.appendingPathComponent(name, isDirectory: true)
      guard Self.isPlainDirectory(session), !FileManager.default.fileExists(atPath: session.appendingPathComponent("tombstone").path),
            let image = committedImage(session, manifestId, captureId, cancelled) else { continue }
      let source = image.source
      guard Self.isPlainFile(source), Self.fileSize(source) == image.length else { continue }
      try? FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
      if edge == 0 {
        if Self.isPlainFile(target), Self.fileSize(target) == image.length,
           let bytes = try? Data(contentsOf: target),
           DurableStoreEncoding.hex(DurableStoreEncoding.sha256([UInt8](bytes))) == image.hash { return !cancelled() }
      } else if CapturePreviewThumbnail.valid(target, edge: edge) { return true }
      let partial = target.deletingLastPathComponent().appendingPathComponent(target.lastPathComponent + ".part")
      defer { try? FileManager.default.removeItem(at: partial) }
      guard !cancelled(), let bytes = try? Data(contentsOf: source),
            DurableStoreEncoding.hex(DurableStoreEncoding.sha256([UInt8](bytes))) == image.hash,
            !cancelled() else { return false }
      if edge == 0 {
        // Verified bytes are copied without decoding, recompression or metadata changes.
        guard (try? bytes.write(to: partial)) != nil else { return false }
      } else if !CapturePreviewThumbnail.write(source: source, target: partial, edge: edge, cancelled: cancelled) { return false }
      guard !cancelled() else { return false }
      return rename(partial.path, target.path) == 0
    }
    return false
  }

  /// The committed JPEG [captureId] of the published root [manifestId]: the
  /// attempt's JPEG when the root's bound committed receipt names
  /// [captureId], or image k of a burst shot root when [captureId] is
  /// [ShotRecord.imageCaptureId] of the receipt's capture id and k.
  private func committedImage(_ session: URL, _ manifestId: String, _ captureId: String, _ cancelled: () -> Bool)
    -> (source: URL, length: Int64, hash: String)? {
    guard let root = committedRoot(session, manifestId, cancelled),
          let bound = boundReceiptCaptureId(session, manifestId, root, cancelled),
          RootedDurableFiles.isValidSegment(bound) else { return nil }
    let assets = session.appendingPathComponent("assets/\(bound)", isDirectory: true)
    if bound == captureId {
      guard let primary = root.components.first(where: { $0.kind == "JPEG" || $0.kind == "HEIC" }) else { return nil }
      return (assets.appendingPathComponent("\(primary.kind.lowercased()).\(primary.hash).blob"), primary.length, primary.hash)
    }
    guard let image = root.shot?.images.first(where: { ShotRecord.imageCaptureId(bound, index: $0.index) == captureId }) else {
      return nil
    }
    return (assets.appendingPathComponent(DurableCaptureStore.shotImageBlobName(index: image.index, hash: image.hash)),
            image.length, image.hash)
  }

  /// The root [wanted] when it is reachable from the selected pointer.
  private func committedRoot(_ session: URL, _ wanted: String, _ cancelled: () -> Bool) -> CaptureRootMetadata? {
    guard let selected = selectedRoot(session) else { return nil }
    var hash = selected.hash
    var revision = selected.revision
    while revision > 0 && !cancelled() {
      guard let root = readRoot(session, hash), root.revision == revision, DurableStoreEncoding.isHash(root.request) else { return nil }
      if revision == 1 && (root.previous != "-" || root.secondPrevious != "-") { return nil }
      if hash == wanted { return root }
      guard let previous = root.previous, DurableStoreEncoding.isHash(previous) else { return nil }
      hash = previous
      revision -= 1
    }
    return nil
  }

  private func selectedRoot(_ session: URL) -> CaptureRootPointer? {
    var selected: CaptureRootPointer?
    for slot in ["A", "B"] {
      let file = session.appendingPathComponent("root-\(slot).ptr")
      guard Self.isPlainFile(file), let data = try? Data(contentsOf: file),
            let pointer = CaptureRootRecords.parsePointer([UInt8](data)),
            validRoot(session, pointer.hash, pointer.revision, 0) else { continue }
      if let prior = selected, prior.revision == pointer.revision, prior.hash != pointer.hash { return nil }
      if selected == nil || pointer.revision > selected!.revision { selected = pointer }
    }
    return selected
  }

  private func validRoot(_ session: URL, _ hash: String, _ revision: Int64, _ depth: Int) -> Bool {
    guard depth <= Self.maxRootDepth, let values = readRoot(session, hash), values.revision == revision,
          DurableStoreEncoding.isHash(values.request) else { return false }
    if depth == Self.maxRootDepth { return true }
    if revision == 1 { return values.previous == "-" && values.secondPrevious == "-" }
    guard let previous = values.previous, previous != "-", validRoot(session, previous, revision - 1, depth + 1) else { return false }
    return depth != 0 || revision < 3 || values.secondPrevious == rootPrevious(session, previous)
  }

  private func rootPrevious(_ session: URL, _ hash: String) -> String? {
    guard let previous = readRoot(session, hash)?.previous, previous != "-" else { return nil }
    return previous
  }

  /// The root alone cannot authorize another capture with equal JPEG bytes:
  /// the committed receipt that names this manifest binds it to its capture
  /// id. Nil when no valid committed receipt names [manifestId].
  private func boundReceiptCaptureId(_ session: URL, _ manifestId: String, _ root: CaptureRootMetadata, _ cancelled: () -> Bool) -> String? {
    let attempts = session.appendingPathComponent("attempts", isDirectory: true)
    guard Self.isPlainDirectory(attempts), let entries = try? FileManager.default.contentsOfDirectory(atPath: attempts.path) else { return nil }
    for name in entries.sorted() {
      if cancelled() { return nil }
      let file = attempts.appendingPathComponent(name).appendingPathComponent("receipt.properties")
      guard Self.isPlainFile(file), let size = Self.fileSize(file), size <= Int64(Self.maxReceiptBytes),
            let data = try? Data(contentsOf: file) else { continue }
      let values = DurableStoreEncoding.parseProperties([UInt8](data))
      guard values["kind"] == CaptureTerminalKind.committedPicture.storedName, values["rootHash"] == manifestId,
            let captureId = values["captureId"], DurableStoreEncoding.isHash(captureId) else { continue }
      guard values["requestHash"] == root.request, values["revision"].flatMap(Int64.init) == root.revision,
            values["receiptHash"] == DurableStoreEncoding.receiptHash(requestHash: root.request ?? "", rootHash: manifestId),
            let jpeg = root.components.first(where: { $0.kind == "JPEG" || $0.kind == "HEIC" }) else { return nil }
      let dng = root.components.first { $0.kind == "DNG" }
      if values.keys.contains(where: { $0.hasPrefix("quality") }) { return nil }
      if let text = values[jpeg.kind == "HEIC" ? "heicSizeBytes" : "jpegSizeBytes"], Int64(text) != jpeg.length { return nil }
      if let text = values["dngSizeBytes"], Int64(text) != dng?.length { return nil }
      return captureId
    }
    return nil
  }

  private func readRoot(_ session: URL, _ hash: String) -> CaptureRootMetadata? {
    guard DurableStoreEncoding.isHash(hash) else { return nil }
    let file = session.appendingPathComponent("objects/\(hash).root")
    guard Self.isPlainFile(file), let data = try? Data(contentsOf: file) else { return nil }
    return CaptureRootRecords.parseRoot(expectedHash: hash, bytes: [UInt8](data))
  }

  static func isPlainFile(_ url: URL) -> Bool {
    var info = stat()
    return lstat(url.path, &info) == 0 && (info.st_mode & S_IFMT) == S_IFREG
  }

  static func isPlainDirectory(_ url: URL) -> Bool {
    var info = stat()
    return lstat(url.path, &info) == 0 && (info.st_mode & S_IFMT) == S_IFDIR
  }

  static func fileSize(_ url: URL) -> Int64? {
    var info = stat()
    return lstat(url.path, &info) == 0 ? Int64(info.st_size) : nil
  }
}

/// Owns the persistent preview copies named by immutable `native-v2://`
/// references (Android `NativeCapturePreviewOwnerV2`): `capture-v2-previews/
/// <manifest>-<capture>.jpg` (512 px) and `.detail.jpg` (1024 px, at most
/// three kept), deletion fences `<key>.deleted` and two-phase deletion
/// transactions. Previews resolve only after the startup reconcile.
final class NativeCapturePreviewOwner {
  private final class Startup {
    let lock = NSLock()
    var reconciled = false
  }

  private static var startups: [String: Startup] = [:]
  private static let startupsLock = NSLock()

  private let root: URL
  private let reader: NativeCapturePreviewReader
  private let startup: Startup

  init(appFiles: URL = NativeCaptureStorePaths.applicationFiles()) {
    root = NativeCaptureStorePaths.previews(appFiles).standardizedFileURL
    reader = NativeCapturePreviewReader(storeRoot: NativeCaptureStorePaths.store(appFiles))
    Self.startupsLock.lock()
    if let existing = Self.startups[root.path] { startup = existing } else {
      startup = Startup()
      Self.startups[root.path] = startup
    }
    Self.startupsLock.unlock()
  }

  func materialize(manifestId: String, captureId: String, edge: Int = 512, cancelled: () -> Bool = { false }) throws -> String? {
    startup.lock.lock(); defer { startup.lock.unlock() }
    guard edge == 0 || edge == 512 || edge == 1024 else { throw DurableStoreStateError("Preview edge must be original, 512 or 1024") }
    let key = try Self.key(manifestId, captureId)
    try checkRoot()
    if cancelled() || !startup.reconciled { return nil }
    if FileManager.default.fileExists(atPath: root.appendingPathComponent("\(key).deleted").path) { return nil }
    let originalExtension = edge == 0 && reader.originalFormat(manifestId: manifestId, captureId: captureId) == "heic" ? "heic" : "jpg"
    let target = try ownedFile(edge == 0 ? "\(key).original.\(originalExtension)" : (edge == 512 ? "\(key).jpg" : "\(key).detail.jpg"))
    guard reader.materialize(manifestId: manifestId, captureId: captureId, target: target, edge: edge, cancelled: cancelled) else { return nil }
    if edge != 512 { trimDetailedCopies(viewed: target) }
    return target.path
  }

  /// Resolves interrupted deletions against the persisted live references.
  func reconcile(liveReferences: [String]) throws {
    startup.lock.lock(); defer { startup.lock.unlock() }
    startup.reconciled = false
    let liveKeys = Set(try liveReferences.map(Self.referenceKey))
    try checkRoot()
    for name in try FileManager.default.contentsOfDirectory(atPath: root.path) where Self.isTransactionName(name) {
      let transaction = try ownedFile(name)
      guard NativeCapturePreviewReader.isPlainDirectory(transaction) else { throw DurableStoreStateError("Preview transaction is not a directory") }
      for pending in try FileManager.default.contentsOfDirectory(atPath: transaction.path) where pending.hasSuffix(".pending") {
        let key = String(pending.dropLast(8))
        guard Self.isKey(key) else { throw DurableStoreStateError("Malformed preview transaction entry") }
        let marker = try ownedFile("\(key).deleted")
        if liveKeys.contains(key) {
          for preview in Self.previewNames(key) {
            let staged = transaction.appendingPathComponent(preview)
            if FileManager.default.fileExists(atPath: staged.path) { try Self.move(staged, try ownedFile(preview)) }
          }
          if FileManager.default.fileExists(atPath: marker.path) { try FileManager.default.removeItem(at: marker) }
        } else {
          try Self.durableWrite(marker)
          for preview in Self.previewNames(key) {
            try? FileManager.default.removeItem(at: transaction.appendingPathComponent(preview))
            try? FileManager.default.removeItem(at: try ownedFile(preview))
          }
        }
        try FileManager.default.removeItem(at: transaction.appendingPathComponent(pending))
      }
      try FileManager.default.removeItem(at: transaction)
    }
    startup.reconciled = true
  }

  /// Fences readers before staging copies; the token names no caller path.
  func beginDeletion(references: [String]) throws -> String {
    startup.lock.lock(); defer { startup.lock.unlock() }
    try checkRoot()
    var keys: [String] = []
    for reference in references {
      let key = try Self.referenceKey(reference)
      if !keys.contains(key) { keys.append(key) }
    }
    let token = UUID().uuidString.lowercased()
    let transaction = try ownedFile("deleting-\(token)")
    try FileManager.default.createDirectory(at: transaction, withIntermediateDirectories: false)
    do {
      for key in keys {
        let marker = try ownedFile("\(key).deleted")
        if FileManager.default.fileExists(atPath: marker.path) { continue }
        try Self.durableWrite(transaction.appendingPathComponent("\(key).pending"))
        try Self.durableWrite(marker)
        for preview in Self.previewNames(key) {
          let file = try ownedFile(preview)
          if FileManager.default.fileExists(atPath: file.path) { try Self.move(file, transaction.appendingPathComponent(preview)) }
        }
      }
      return token
    } catch {
      try? rollbackLocked(token)
      throw error
    }
  }

  func commitDeletion(token: String) throws {
    startup.lock.lock(); defer { startup.lock.unlock() }
    let transaction = try self.transaction(token)
    if FileManager.default.fileExists(atPath: transaction.path) { try FileManager.default.removeItem(at: transaction) }
  }

  func rollbackDeletion(token: String) throws {
    startup.lock.lock(); defer { startup.lock.unlock() }
    try rollbackLocked(token)
  }

  private func rollbackLocked(_ token: String) throws {
    let transaction = try self.transaction(token)
    let entries = (try? FileManager.default.contentsOfDirectory(atPath: transaction.path)) ?? []
    for pending in entries where pending.hasSuffix(".pending") {
      let key = String(pending.dropLast(8))
      guard Self.isKey(key) else { throw DurableStoreStateError("Malformed preview transaction entry") }
      for preview in Self.previewNames(key) {
        let staged = transaction.appendingPathComponent(preview)
        if FileManager.default.fileExists(atPath: staged.path) { try Self.move(staged, try ownedFile(preview)) }
      }
      let marker = try ownedFile("\(key).deleted")
      if FileManager.default.fileExists(atPath: marker.path) { try FileManager.default.removeItem(at: marker) }
    }
    if FileManager.default.fileExists(atPath: transaction.path) { try FileManager.default.removeItem(at: transaction) }
  }

  private func transaction(_ token: String) throws -> URL {
    guard let uuid = UUID(uuidString: token), uuid.uuidString.lowercased() == token else {
      throw DurableStoreStateError("Invalid preview deletion token")
    }
    try checkRoot()
    return try ownedFile("deleting-\(token)")
  }

  private func checkRoot() throws {
    var info = stat()
    if lstat(root.path, &info) == 0 {
      guard (info.st_mode & S_IFMT) == S_IFDIR else { throw DurableStoreStateError("Preview directory must not be a symbolic link") }
      return
    }
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
  }

  private func ownedFile(_ name: String) throws -> URL {
    let file = root.appendingPathComponent(name)
    var info = stat()
    if lstat(file.path, &info) == 0, (info.st_mode & S_IFMT) == S_IFLNK {
      throw DurableStoreStateError("Preview files must not be symbolic links")
    }
    return file
  }

  /// Keeps the viewed detail/original copy plus at most two others.
  private func trimDetailedCopies(viewed: URL) {
    try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: viewed.path)
    let names = ((try? FileManager.default.contentsOfDirectory(atPath: root.path)) ?? []).filter { $0.hasSuffix(".detail.jpg") || $0.hasSuffix(".original.jpg") || $0.hasSuffix(".original.heic") }
    let others = names.map { root.appendingPathComponent($0) }.filter { $0.lastPathComponent != viewed.lastPathComponent }
    guard others.count > 2 else { return }
    let dated = others.map { url -> (URL, Date) in
      let date = (try? FileManager.default.attributesOfItem(atPath: url.path))?[.modificationDate] as? Date ?? .distantPast
      return (url, date)
    }.sorted { $0.1 > $1.1 }
    for (url, _) in dated.dropFirst(2) { try? FileManager.default.removeItem(at: url) }
  }

  private static func move(_ from: URL, _ to: URL) throws {
    guard rename(from.path, to.path) == 0 else { throw DurableFileError(operation: "rename preview", code: errno) }
  }

  private static func durableWrite(_ url: URL) throws {
    let fd = open(url.path, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW | O_CLOEXEC, 0o600)
    guard fd >= 0 else { throw DurableFileError(operation: "create marker", code: errno) }
    defer { close(fd) }
    try PosixDurableFileBackend.fullSync(fd)
  }

  private static func previewNames(_ key: String) -> [String] { ["\(key).jpg", "\(key).detail.jpg", "\(key).original.jpg", "\(key).original.heic"] }

  static func key(_ manifestId: String, _ captureId: String) throws -> String {
    guard DurableStoreEncoding.isHash(manifestId), DurableStoreEncoding.isHash(captureId) else {
      throw DurableStoreStateError("Invalid immutable preview reference")
    }
    return "\(manifestId)-\(captureId)"
  }

  static func referenceKey(_ reference: String) throws -> String {
    let prefix = "native-v2://"
    guard reference.hasPrefix(prefix) else { throw DurableStoreStateError("Invalid immutable preview reference") }
    let parts = reference.dropFirst(prefix.count).split(separator: "/", omittingEmptySubsequences: false).map(String.init)
    guard parts.count == 2 else { throw DurableStoreStateError("Invalid immutable preview reference") }
    return try key(parts[0], parts[1])
  }

  private static func isKey(_ value: String) -> Bool {
    let parts = value.split(separator: "-", omittingEmptySubsequences: false).map(String.init)
    return parts.count == 2 && DurableStoreEncoding.isHash(parts[0]) && DurableStoreEncoding.isHash(parts[1])
  }

  private static func isTransactionName(_ name: String) -> Bool {
    guard name.hasPrefix("deleting-") else { return false }
    let token = String(name.dropFirst(9))
    return UUID(uuidString: token)?.uuidString.lowercased() == token
  }
}


/// Reads only the authorized, native-downsampled JPEG copy, never an original.
/// The geometry descriptor records the actual source raster and EXIF transform.
enum GuidancePreviewLuma {
  struct SourceGeometry: Equatable {
    let width: Int
    let height: Int
    let exifOrientation: Int
  }

  /// ImageIO can promote TIFF ImageDescription to IPTC Caption/Abstract when
  /// encoding JPEG. Accept only these two native round-trip locations, with
  /// the same bounded versioned descriptor; conflicting values are unknown.
  static func sourceGeometry(_ properties: [CFString: Any]) -> SourceGeometry? {
    let tiff = properties[kCGImagePropertyTIFFDictionary] as? [CFString: Any]
    let iptc = properties[kCGImagePropertyIPTCDictionary] as? [CFString: Any]
    let tiffDescription = tiff?[kCGImagePropertyTIFFImageDescription] as? String
    let caption = iptc?[kCGImagePropertyIPTCCaptionAbstract] as? String
    if let tiffDescription = tiffDescription, let caption = caption, tiffDescription != caption { return nil }
    guard let description = tiffDescription ?? caption, description.count <= 80 else { return nil }
    let parts = description.split(separator: ":", omittingEmptySubsequences: false)
    guard parts.count == 4, parts[0] == "guidance_preview_v1",
      parts.dropFirst().allSatisfy({ !$0.isEmpty && $0.allSatisfy({ $0 >= "0" && $0 <= "9" }) }),
      let width = Int(parts[1]), let height = Int(parts[2]), let orientation = Int(parts[3]),
      (1...65536).contains(width), (1...65536).contains(height), (1...8).contains(orientation) else { return nil }
    return SourceGeometry(width: width, height: height, exifOrientation: orientation)
  }

  static func read(_ path: String, cancelled: () -> Bool) -> [String: Any]? {
    let url = URL(fileURLWithPath: path)
    guard CapturePreviewThumbnail.valid(url, edge: 512), !cancelled(),
      let source = CGImageSourceCreateWithURL(url as CFURL, nil),
      let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
      let geometry = sourceGeometry(properties),
      let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { return nil }
    let scale = min(1, 256 / Double(max(image.width, image.height)))
    let width = max(1, Int(Double(image.width) * scale)), height = max(1, Int(Double(image.height) * scale))
    var luma = Data(count: width * height)
    let drawn = luma.withUnsafeMutableBytes { buffer -> Bool in
      guard let context = CGContext(data: buffer.baseAddress, width: width, height: height,
        bitsPerComponent: 8, bytesPerRow: width, space: CGColorSpaceCreateDeviceGray(), bitmapInfo: 0) else { return false }
      context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
      return true
    }
    guard drawn, !cancelled() else { return nil }
    return ["wireVersion": "guidance_luma_v1", "width": width, "height": height,
      "sourceWidth": geometry.width, "sourceHeight": geometry.height, "exifOrientation": geometry.exifOrientation,
      "luma": FlutterStandardTypedData(bytes: luma)]
  }
}
