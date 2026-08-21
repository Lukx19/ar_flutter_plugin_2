import Foundation

/// Declaration-only future iOS seam for the M0 worker-pull transport.
///
/// M0 intentionally does not implement or test Swift behavior. Android and
/// Dart own the executable reference while the future Apple implementation
/// remains a separate ticket.
protocol M0PortableSurfaceStreamInterface {
    func exchange(request: Data) async throws -> Data
    func stop(request: Data) async throws -> Data
}

/// Declaration-only future iOS seam for control, replay, and lifecycle.
protocol M0PortableControlInterface {
    func start(request: Data) async throws -> Data
    func beginCheckpoint(request: Data) async throws -> Data
    func releaseCheckpoint(request: Data) async throws -> Data
    func stop(request: Data) async throws -> Data
}

/// Declaration-only production V2 binding seam. Implementing these methods,
/// registering channels, or decoding packets on Apple platforms is deferred.
protocol VisibilityGridV2BindingInterface {
    func start(request: Data) async throws -> Data
    func exchange(request: Data) async throws -> Data
    func beginCheckpoint(request: Data) async throws -> Data
    func releaseCheckpoint(request: Data) async throws -> Data
    func stop(request: Data) async throws -> Data
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
    static let exchangeRequestHeaderBytes = 80
    static let exchangeResponseHeaderBytes = 112
    static let controlRequestHeaderBytes = 104
    static let controlResponseHeaderBytes = 128
    static let startRequestBytes = 464
    static let startResultBytes = 184
}

/// Shape-only immutable group and runtime-binding identity.
struct VisibilityGridV2BindingIdentity {
    let sessionID: UUID
    let sessionGeneration: UInt64
    let captureGroupID: UUID
    let groupGeneration: UInt64
    let coverageEpoch: UInt64
    let streamToken: UInt64
    let bindingGeneration: UInt64
}

/// Shape-only control receipt shared by START/checkpoint/release/stop.
struct VisibilityGridV2ControlReceiptDescriptor {
    let operation: UInt8
    let controlRequestID: UUID
    let binding: VisibilityGridV2BindingIdentity
    let requestAccepted: Bool
    let authorityMutated: Bool
    let nextExchangeRequestSequence: UInt64
    let nativeTransactionID: UInt64
    let payload: Data
}

/// Shape-only START payload. Matrices are declared as fixed-convention lanes;
/// no validation, codec, or ARKit adapter is implemented here.
struct VisibilityGridV2StartDescriptor {
    let minimumMinor: UInt16
    let maximumMinor: UInt16
    let persistenceSchema: UInt16
    let requiredCapabilities: UInt64
    let desiredCapabilities: UInt64
    let groupFromWorldColumnMajor: [Double]
    let worldFromGroupColumnMajor: [Double]
    let restoredRevisionVector: [UInt64]
    let schemaRootSHA256: Data
    let manifestRootSHA256: Data
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

/// Declaration-only future iOS seam for M0d's selected centroid renderer.
///
/// This freezes the platform boundary without adding a Swift renderer,
/// SceneKit/ARKit adapter, lifecycle implementation, or executable behavior.
protocol M0PortableCentroidRendererInterface {
    func replaceCommittedCut(_ descriptor: M0PortableRendererCutDescriptor) async throws
    func setPresentation(_ descriptor: M0PortableRendererPresentationDescriptor) async throws
    func disposeRendererGeneration(_ generation: UInt64) async
}

/// Shape-only committed semantic-cut descriptor for a future renderer owner.
struct M0PortableRendererCutDescriptor {
    let rendererGeneration: UInt64
    let geometryRevision: UInt64
    let visibilityRevision: UInt64
    let packedRows: Data
}

/// Shape-only presentation descriptor. Semantic truth remains outside the
/// renderer and these fields intentionally contain no rendering algorithm.
struct M0PortableRendererPresentationDescriptor {
    let rendererGeneration: UInt64
    let mode: String
    let pointsEnabled: Bool
    let guidanceEnabled: Bool
}
