import CryptoKit
import Darwin
import Foundation

/// Every durable write step of the capture store, in the Android
/// `DurableStoreFaultPointV2` order. Tests inject a failure at each one.
enum DurableStoreFaultPoint: String, CaseIterable {
  case acceptedRecord, componentOpen, partCreate, partWrite, partHash, lengthCheck
  case partFileSync, assetRename, assetDirectorySync, rootWrite, rootFileSync
  case receiptWrite, receiptFileSync, pointerSlotReplace, pointerDirectorySync
  case abandonmentRecord, abandonmentCleanup, tombstoneRecord, tombstoneDirectorySync
  case deleteReclaim, recovery
}

/// Called before each durable step; throwing simulates a crash at that step.
typealias DurableStoreFaultInjector = (DurableStoreFaultPoint) throws -> Void

/// The pointer rename happened, but its directory sync did not: whether the
/// switch is durable is unknown.
struct PointerDirectorySyncUnknown: Error {
  let cause: Error
}

struct DurableFileError: Error, CustomStringConvertible {
  let operation: String
  let code: Int32
  var description: String { "\(operation) failed: \(String(cString: strerror(code)))" }
}

/// Root-relative file operations of the durable store. Paths are lists of
/// validated name segments under one bound root; nothing follows symbolic
/// links. A test backend wraps the POSIX one to fail or count operations.
protocol DurableFileBackend: AnyObject {
  func ensureDirectory(_ path: [String]) throws
  func isRegularFile(_ path: [String]) -> Bool
  func isDirectory(_ path: [String]) -> Bool
  func size(_ path: [String]) throws -> Int64
  func readAll(_ path: [String]) throws -> [UInt8]
  /// Creates a new file (fails when it exists), writes [chunks] in order and
  /// syncs it; [beforeChunk] runs before each chunk, [beforeSync] before the sync.
  func createExclusive(_ path: [String], chunks: [ArraySlice<UInt8>], beforeChunk: () throws -> Void, beforeSync: () throws -> Void) throws
  /// Creates a physically allocated file of exactly [bytes] bytes and syncs it.
  func allocateExclusive(_ path: [String], bytes: Int64) throws
  /// rename(2) within one directory, replacing the target.
  func atomicReplace(parent: [String], from: String, to: String) throws
  /// rename(2) between directories.
  func atomicMove(fromParent: [String], from: String, toParent: [String], to: String) throws
  /// unlink or rmdir; a missing entry is not an error.
  func delete(_ path: [String]) throws
  func list(_ path: [String]) throws -> [String]
  func syncDirectory(_ path: [String]) throws
}

/// Production backend: POSIX calls under one root with `O_NOFOLLOW`, and
/// `F_FULLFSYNC` wherever Android calls `fsync` (falls back to `fsync` when
/// the file system refuses the full barrier).
final class PosixDurableFileBackend: DurableFileBackend {
  let root: URL

  init(root: URL) throws {
    self.root = root.standardizedFileURL
    try FileManager.default.createDirectory(at: self.root, withIntermediateDirectories: true)
    var info = stat()
    guard lstat(self.root.path, &info) == 0, (info.st_mode & S_IFMT) == S_IFDIR else {
      throw DurableFileError(operation: "bind root", code: ENOTDIR)
    }
  }

  private func path(_ segments: [String]) -> String {
    segments.reduce(root.path) { $0 + "/" + $1 }
  }

  private func lstatMode(_ segments: [String]) -> mode_t? {
    var info = stat()
    guard lstat(path(segments), &info) == 0 else { return nil }
    return info.st_mode & S_IFMT
  }

  func ensureDirectory(_ segments: [String]) throws {
    var current: [String] = []
    for segment in segments {
      current.append(segment)
      if let mode = lstatMode(current) {
        guard mode == S_IFDIR else { throw DurableFileError(operation: "ensure directory", code: ENOTDIR) }
        continue
      }
      if mkdir(path(current), 0o700) != 0 && errno != EEXIST {
        throw DurableFileError(operation: "mkdir", code: errno)
      }
      guard lstatMode(current) == S_IFDIR else { throw DurableFileError(operation: "ensure directory", code: ENOTDIR) }
    }
  }

  func isRegularFile(_ segments: [String]) -> Bool { lstatMode(segments) == S_IFREG }
  func isDirectory(_ segments: [String]) -> Bool { segments.isEmpty || lstatMode(segments) == S_IFDIR }

  func size(_ segments: [String]) throws -> Int64 {
    var info = stat()
    guard lstat(path(segments), &info) == 0 else { throw DurableFileError(operation: "stat", code: errno) }
    return Int64(info.st_size)
  }

  func readAll(_ segments: [String]) throws -> [UInt8] {
    let fd = open(path(segments), O_RDONLY | O_NOFOLLOW | O_CLOEXEC)
    guard fd >= 0 else { throw DurableFileError(operation: "open read", code: errno) }
    defer { close(fd) }
    var out = [UInt8]()
    var buffer = [UInt8](repeating: 0, count: 64 * 1024)
    while true {
      let count = buffer.withUnsafeMutableBytes { read(fd, $0.baseAddress, $0.count) }
      if count < 0 {
        if errno == EINTR { continue }
        throw DurableFileError(operation: "read", code: errno)
      }
      if count == 0 { break }
      out += buffer[0..<count]
    }
    return out
  }

  func createExclusive(_ segments: [String], chunks: [ArraySlice<UInt8>], beforeChunk: () throws -> Void, beforeSync: () throws -> Void) throws {
    let fd = open(path(segments), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0o600)
    guard fd >= 0 else { throw DurableFileError(operation: "create exclusive", code: errno) }
    defer { close(fd) }
    for chunk in chunks {
      try beforeChunk()
      try Self.writeAll(fd, chunk)
    }
    try beforeSync()
    try Self.fullSync(fd)
  }

  func allocateExclusive(_ segments: [String], bytes: Int64) throws {
    guard bytes > 0 else { throw DurableFileError(operation: "allocate", code: EINVAL) }
    let fd = open(path(segments), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0o600)
    guard fd >= 0 else { throw DurableFileError(operation: "create allocation", code: errno) }
    defer { close(fd) }
    // Reserve real blocks (APFS F_PREALLOCATE) instead of writing zeros; fall
    // back to writing zeros so the reservation is never sparse.
    var store = fstore_t(fst_flags: UInt32(F_ALLOCATEALL), fst_posmode: F_PEOFPOSMODE, fst_offset: 0, fst_length: off_t(bytes), fst_bytesalloc: 0)
    if fcntl(fd, F_PREALLOCATE, &store) == -1 {
      store.fst_flags = UInt32(F_ALLOCATECONTIG | F_ALLOCATEALL)
      if fcntl(fd, F_PREALLOCATE, &store) == -1 {
        let zeros = [UInt8](repeating: 0, count: 64 * 1024)
        var remaining = bytes
        while remaining > 0 {
          let count = Int(min(Int64(zeros.count), remaining))
          try Self.writeAll(fd, zeros[0..<count])
          remaining -= Int64(count)
        }
      }
    }
    if ftruncate(fd, off_t(bytes)) != 0 { throw DurableFileError(operation: "ftruncate", code: errno) }
    try Self.fullSync(fd)
  }

  func atomicReplace(parent: [String], from: String, to: String) throws {
    if rename(path(parent + [from]), path(parent + [to])) != 0 {
      throw DurableFileError(operation: "rename replace", code: errno)
    }
  }

  func atomicMove(fromParent: [String], from: String, toParent: [String], to: String) throws {
    if rename(path(fromParent + [from]), path(toParent + [to])) != 0 {
      throw DurableFileError(operation: "rename move", code: errno)
    }
  }

  func delete(_ segments: [String]) throws {
    guard let mode = lstatMode(segments) else { return }
    let result = mode == S_IFDIR ? rmdir(path(segments)) : unlink(path(segments))
    if result != 0 && errno != ENOENT { throw DurableFileError(operation: "delete", code: errno) }
  }

  func list(_ segments: [String]) throws -> [String] {
    guard isDirectory(segments) else { throw DurableFileError(operation: "list", code: ENOTDIR) }
    return try FileManager.default.contentsOfDirectory(atPath: path(segments)).sorted()
  }

  func syncDirectory(_ segments: [String]) throws {
    let fd = open(path(segments), O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_DIRECTORY)
    guard fd >= 0 else { throw DurableFileError(operation: "open directory", code: errno) }
    defer { close(fd) }
    try Self.fullSync(fd)
  }

  private static func writeAll(_ fd: Int32, _ chunk: ArraySlice<UInt8>) throws {
    try chunk.withUnsafeBytes { raw in
      var offset = 0
      while offset < raw.count {
        let written = write(fd, raw.baseAddress! + offset, raw.count - offset)
        if written < 0 {
          if errno == EINTR { continue }
          throw DurableFileError(operation: "write", code: errno)
        }
        offset += written
      }
    }
  }

  static func fullSync(_ fd: Int32) throws {
    if fcntl(fd, F_FULLFSYNC) == 0 { return }
    if fsync(fd) != 0 { throw DurableFileError(operation: "fsync", code: errno) }
  }
}

/// The store's safe file layer (Android `SafeFilesystemV2`): validated names,
/// exclusive creates, write then sync then parent directory sync, and the
/// fault points of every step.
final class RootedDurableFiles {
  let backend: DurableFileBackend
  private let fault: DurableStoreFaultInjector
  static let chunkBytes = 64 * 1024

  init(backend: DurableFileBackend, fault: @escaping DurableStoreFaultInjector = { _ in }) {
    self.backend = backend
    self.fault = fault
  }

  /// Name segments: 1-240 characters of `[A-Za-z0-9._-]`, never `.` or `..`.
  static func isValidSegment(_ name: String) -> Bool {
    guard (1...240).contains(name.utf8.count), name != ".", name != ".." else { return false }
    return name.utf8.allSatisfy {
      ($0 >= 0x41 && $0 <= 0x5a) || ($0 >= 0x61 && $0 <= 0x7a) || ($0 >= 0x30 && $0 <= 0x39) || $0 == 0x2e || $0 == 0x5f || $0 == 0x2d
    }
  }

  private func checked(_ path: [String]) throws -> [String] {
    guard path.allSatisfy(Self.isValidSegment) else {
      throw DurableStoreStateError("Durable path segment is not canonical")
    }
    return path
  }

  func at(_ point: DurableStoreFaultPoint) throws { try fault(point) }

  func ensureDirectory(_ path: [String]) throws { try backend.ensureDirectory(checked(path)) }
  func isFile(_ path: [String]) -> Bool { (try? checked(path)).map(backend.isRegularFile) ?? false }
  func isDirectory(_ path: [String]) -> Bool { (try? checked(path)).map(backend.isDirectory) ?? false }
  func length(_ path: [String]) throws -> Int64 { try backend.size(checked(path)) }
  func readBytes(_ path: [String]) throws -> [UInt8] { try backend.readAll(checked(path)) }
  func list(_ path: [String]) throws -> [String] { try backend.list(checked(path)) }

  /// Every path under [directory] including itself, parents before children.
  func walk(_ directory: [String]) throws -> [[String]] {
    var out: [[String]] = [directory]
    guard backend.isDirectory(try checked(directory)) else { return out }
    for name in try backend.list(directory) {
      let child = directory + [name]
      if backend.isDirectory(child) { out += try walk(child) } else { out.append(child) }
    }
    return out
  }

  func writeExclusive(_ path: [String], _ bytes: [UInt8], _ point: DurableStoreFaultPoint) throws {
    let path = try checked(path)
    try backend.ensureDirectory(Array(path.dropLast()))
    try fault(point)
    try backend.createExclusive(path, chunks: [bytes[...]], beforeChunk: {}, beforeSync: {})
    try backend.syncDirectory(Array(path.dropLast()))
  }

  /// Writes once; an existing file must hold exactly [bytes].
  func writeImmutable(_ path: [String], _ bytes: [UInt8], _ point: DurableStoreFaultPoint) throws {
    if isFile(path) {
      guard try readBytes(path) == bytes else { throw DurableStoreStateError("Immutable record conflict") }
      return
    }
    try writeExclusive(path, bytes, point)
  }

  /// Temp `.<name>.part` write, sync, rename over [path], parent directory sync.
  func atomicReplace(
    _ path: [String], _ bytes: [UInt8], replacePoint: DurableStoreFaultPoint?,
    directorySyncPoint: DurableStoreFaultPoint? = .pointerDirectorySync
  ) throws {
    let path = try checked(path)
    let parent = Array(path.dropLast())
    let temporary = ".\(path.last!).part"
    try backend.ensureDirectory(parent)
    try backend.delete(parent + [temporary])
    try backend.createExclusive(parent + [temporary], chunks: [bytes[...]], beforeChunk: {}, beforeSync: {})
    if let point = replacePoint { try fault(point) }
    try backend.atomicReplace(parent: parent, from: temporary, to: path.last!)
    do {
      if let point = directorySyncPoint { try fault(point) }
      try backend.syncDirectory(parent)
    } catch {
      throw PointerDirectorySyncUnknown(cause: error)
    }
  }

  func moveAtomic(_ from: [String], _ to: [String]) throws {
    let from = try checked(from), to = try checked(to)
    try backend.ensureDirectory(Array(to.dropLast()))
    try fault(.assetRename)
    try backend.atomicMove(fromParent: Array(from.dropLast()), from: from.last!, toParent: Array(to.dropLast()), to: to.last!)
    try fault(.assetDirectorySync)
    try backend.syncDirectory(Array(to.dropLast()))
  }

  /// Streams [bytes] into a new part file while hashing; returns length and SHA-256.
  func streamExclusive(_ path: [String], _ bytes: [UInt8]) throws -> (Int64, [UInt8]) {
    let path = try checked(path)
    try backend.ensureDirectory(Array(path.dropLast()))
    try fault(.partCreate)
    var hasher = SHA256()
    var chunks: [ArraySlice<UInt8>] = []
    var offset = 0
    while offset < bytes.count {
      let end = min(offset + Self.chunkBytes, bytes.count)
      chunks.append(bytes[offset..<end])
      offset = end
    }
    try backend.createExclusive(path, chunks: chunks, beforeChunk: { try self.fault(.partWrite) }, beforeSync: { try self.fault(.partFileSync) })
    chunks.forEach { chunk in chunk.withUnsafeBytes { hasher.update(bufferPointer: $0) } }
    try fault(.partHash)
    try backend.syncDirectory(Array(path.dropLast()))
    return (Int64(bytes.count), Array(hasher.finalize()))
  }

  func digestAndLength(_ path: [String]) throws -> (Int64, [UInt8]) {
    let bytes = try readBytes(path)
    return (Int64(bytes.count), DurableStoreEncoding.sha256(bytes))
  }

  func allocateExclusive(_ path: [String], bytes: Int64) throws {
    let path = try checked(path)
    try backend.ensureDirectory(Array(path.dropLast()))
    try backend.allocateExclusive(path, bytes: bytes)
    guard try backend.size(path) == bytes else { throw DurableStoreStateError("Allocation size mismatch") }
    try backend.syncDirectory(Array(path.dropLast()))
  }

  func delete(_ path: [String], _ point: DurableStoreFaultPoint) throws {
    let path = try checked(path)
    try fault(point)
    try backend.delete(path)
    try backend.syncDirectory(Array(path.dropLast()))
  }

  func deleteTree(_ directory: [String], _ point: DurableStoreFaultPoint) throws {
    let directory = try checked(directory)
    try fault(point)
    if !backend.isDirectory(directory) {
      try backend.delete(directory)
      return
    }
    for path in try walk(directory).reversed() { try backend.delete(path) }
    try backend.syncDirectory(Array(directory.dropLast()))
  }
}
