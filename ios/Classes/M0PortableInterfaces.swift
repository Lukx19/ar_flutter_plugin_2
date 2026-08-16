import Foundation

/// Declaration-only future iOS seam for the M0 worker-pull transport.
///
/// M0 intentionally does not implement or test Swift behavior. Android and
/// Dart own the executable reference while the future Apple implementation
/// remains a separate ticket.
protocol M0PortableSurfaceStreamInterface {
    func exchange(request: Data) async throws -> Data
    func stop() async
}

/// Declaration-only future iOS seam for control, replay, and lifecycle.
protocol M0PortableControlInterface {
    func start(request: Data) async throws -> Data
    func replay(request: Data) async throws -> Data
    func stop() async
}

/// Wire constants shared by the future interface and the executable Android
/// reference. They describe the seam without providing a Swift codec.
enum M0PortableInterfaceConstants {
    static let surfaceRequestMagic = "VGR2"
    static let surfaceResponseMagic = "VGS2"
    static let controlRequestMagic = "VGC2"
    static let controlResponseMagic = "VGD2"
    static let maximumRequestBytes = 16 * 1024
    static let maximumDiagnosticBytes = 1024
}

/// Shape-only DTO for a future native response handoff.
struct M0PortableResponseDescriptor {
    let streamToken: UInt64
    let requestSequence: UInt64
    let transactionID: UInt64
    let geometryRevision: UInt64
    let lineageRevision: UInt64
    let payload: Data
}
