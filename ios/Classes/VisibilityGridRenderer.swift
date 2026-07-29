import Foundation
import SceneKit

struct VisibilityGridRenderSnapshot {
    let enabled: Bool
    let mode: VisibilityGridRenderMode
    let voxelSizeMeters: Double
    let pointSizePixels: CGFloat
    let cubeSizeFactor: Double
    let keys: [UInt64]
    let positionsWorld: [Float]
    let colorsARGB: [UInt32]
}

final class VisibilityGridRendererState {
    static let maximumWorstCaseCapacity = 8_000

    let capacity: Int
    let defaultColor: UInt32
    var enabled = true
    var mode: VisibilityGridRenderMode = .centroids
    var pointSizePixels: CGFloat = 6
    var cubeSizeFactor = 0.8

    private var keys: [UInt64] = []
    private var positionsWorld: [Float] = []
    private var colorsARGB: [UInt32] = []
    private var rowsByKey: [UInt64: Int] = [:]
    private var voxelSizeMeters = 0.1
    private var worldFromGroupGL = identityVisibilityGridTransform()
    private(set) var geometryRevision: Int64 = 0
    private(set) var visibilityRevision: Int64 = 0
    private(set) var ignoredDeletedVisibilityKeys: Int64 = 0
    private(set) var disposed = false
    private var groupActive = false

    var freeRowCount: Int {
        capacity - keys.count
    }

    init(
        capacity: Int,
        defaultColor: UInt32 = 0xffff0000
    ) throws {
        guard (1...Self.maximumWorstCaseCapacity).contains(capacity) else {
            throw VisibilityGridContractError.invalidArgument(
                "Invalid renderer capacity"
            )
        }
        self.capacity = capacity
        self.defaultColor = defaultColor
        keys.reserveCapacity(capacity)
        positionsWorld.reserveCapacity(capacity * 3)
        colorsARGB.reserveCapacity(capacity)
        rowsByKey.reserveCapacity(capacity)
    }

    func startGroup(
        voxelSizeMeters: Double,
        worldFromGroupGL: [Double],
        geometryRevision: Int64,
        visibilityRevision: Int64,
        restoredKeys: [UInt64]
    ) throws {
        try ensureActive()
        guard voxelSizeMeters.isFinite && voxelSizeMeters > 0,
            worldFromGroupGL.count == 16,
            restoredKeys.count <= capacity,
            Set(restoredKeys).count == restoredKeys.count,
            geometryRevision >= 0,
            visibilityRevision >= 0
        else {
            throw VisibilityGridContractError.invalidArgument(
                "Invalid renderer group"
            )
        }
        clearRows()
        self.voxelSizeMeters = voxelSizeMeters
        self.worldFromGroupGL = worldFromGroupGL
        self.geometryRevision = geometryRevision
        self.visibilityRevision = visibilityRevision
        ignoredDeletedVisibilityKeys = 0
        groupActive = true
        for key in restoredKeys {
            append(key)
        }
    }

    @discardableResult
    func applyGeometry(
        revision: Int64,
        reset: Bool,
        upsertKeys: [UInt64],
        removalKeys: [UInt64]
    ) -> Bool {
        guard !disposed, groupActive,
            reset
                ? revision > geometryRevision
                : revision == geometryRevision + 1,
            Set(upsertKeys).count == upsertKeys.count,
            Set(removalKeys).count == removalKeys.count,
            Set(upsertKeys).isDisjoint(with: Set(removalKeys))
        else {
            return false
        }
        var finalKeys = reset ? Set<UInt64>() : Set(keys)
        finalKeys.subtract(removalKeys)
        finalKeys.formUnion(upsertKeys)
        guard finalKeys.count <= capacity else { return false }
        if reset {
            clearRows()
        } else {
            for key in removalKeys {
                remove(key)
            }
        }
        for key in upsertKeys where rowsByKey[key] == nil {
            append(key)
        }
        geometryRevision = revision
        return true
    }

    @discardableResult
    func applyVisibility(
        geometryRevision namedGeometryRevision: Int64,
        visibilityRevision nextVisibilityRevision: Int64,
        keys patchKeys: [UInt64],
        colors patchColors: [UInt32]
    ) -> Bool {
        guard !disposed, groupActive,
            namedGeometryRevision == geometryRevision,
            nextVisibilityRevision > visibilityRevision,
            patchKeys.count == patchColors.count,
            Set(patchKeys).count == patchKeys.count
        else {
            return false
        }
        for index in patchKeys.indices {
            guard let row = rowsByKey[patchKeys[index]] else {
                ignoredDeletedVisibilityKeys += 1
                continue
            }
            colorsARGB[row] = patchColors[index]
        }
        visibilityRevision = nextVisibilityRevision
        return true
    }

    func snapshot() -> VisibilityGridRenderSnapshot {
        VisibilityGridRenderSnapshot(
            enabled: enabled,
            mode: mode,
            voxelSizeMeters: voxelSizeMeters,
            pointSizePixels: pointSizePixels,
            cubeSizeFactor: cubeSizeFactor,
            keys: keys,
            positionsWorld: positionsWorld,
            colorsARGB: colorsARGB
        )
    }

    func stopGroup() {
        guard !disposed else { return }
        clearRows()
        groupActive = false
        geometryRevision = 0
        visibilityRevision = 0
        ignoredDeletedVisibilityKeys = 0
    }

    func dispose() {
        guard !disposed else { return }
        stopGroup()
        disposed = true
    }

    private func append(_ key: UInt64) {
        precondition(keys.count < capacity)
        let row = keys.count
        keys.append(key)
        colorsARGB.append(defaultColor)
        positionsWorld.append(contentsOf: worldPosition(for: key))
        rowsByKey[key] = row
    }

    private func remove(_ key: UInt64) {
        guard let row = rowsByKey.removeValue(forKey: key) else {
            return
        }
        let last = keys.count - 1
        if row != last {
            let movedKey = keys[last]
            keys[row] = movedKey
            colorsARGB[row] = colorsARGB[last]
            for axis in 0..<3 {
                positionsWorld[row * 3 + axis] =
                    positionsWorld[last * 3 + axis]
            }
            rowsByKey[movedKey] = row
        }
        keys.removeLast()
        colorsARGB.removeLast()
        positionsWorld.removeSubrange(
            (positionsWorld.count - 3)..<positionsWorld.count
        )
    }

    private func clearRows() {
        keys.removeAll(keepingCapacity: true)
        positionsWorld.removeAll(keepingCapacity: true)
        colorsARGB.removeAll(keepingCapacity: true)
        rowsByKey.removeAll(keepingCapacity: true)
    }

    private func worldPosition(for key: UInt64) -> [Float] {
        let coordinates = unpackVisibilityGridKey(key)
        let half = voxelSizeMeters / 2
        let group = VisibilityGridPoint(
            x: Double(coordinates[0]) * voxelSizeMeters + half,
            y: Double(coordinates[1]) * voxelSizeMeters + half,
            z: Double(coordinates[2]) * voxelSizeMeters + half
        )
        let world = VisibilityGridPoint.transform(
            matrix: worldFromGroupGL,
            point: group
        )
        return [Float(world.x), Float(world.y), Float(world.z)]
    }

    private func ensureActive() throws {
        if disposed {
            throw VisibilityGridContractError.disposed
        }
    }
}

final class VisibilityGridSceneRenderer {
    private weak var rootNode: SCNNode?
    private let node = SCNNode()
    private var disposed = false

    init(rootNode: SCNNode) {
        self.rootNode = rootNode
        node.name = "capture3d_visibility_grid"
        rootNode.addChildNode(node)
    }

    func render(_ snapshot: VisibilityGridRenderSnapshot?) {
        precondition(Thread.isMainThread)
        guard !disposed else { return }
        guard let snapshot, snapshot.enabled, !snapshot.keys.isEmpty else {
            node.geometry = nil
            node.isHidden = true
            return
        }
        node.isHidden = false
        switch snapshot.mode {
        case .points, .centroids:
            node.geometry = pointGeometry(snapshot)
        case .cubes:
            node.geometry = cubeGeometry(snapshot)
        }
    }

    func dispose() {
        precondition(Thread.isMainThread)
        guard !disposed else { return }
        disposed = true
        node.geometry = nil
        node.removeFromParentNode()
    }

    private func pointGeometry(
        _ snapshot: VisibilityGridRenderSnapshot
    ) -> SCNGeometry {
        let vertexData = snapshot.positionsWorld.withUnsafeBufferPointer {
            Data(buffer: $0)
        }
        let vertices = SCNGeometrySource(
            data: vertexData,
            semantic: .vertex,
            vectorCount: snapshot.keys.count,
            usesFloatComponents: true,
            componentsPerVector: 3,
            bytesPerComponent: MemoryLayout<Float>.size,
            dataOffset: 0,
            dataStride: MemoryLayout<Float>.size * 3
        )
        let colorBytes = rgbaBytes(snapshot.colorsARGB)
        let colors = SCNGeometrySource(
            data: Data(colorBytes),
            semantic: .color,
            vectorCount: snapshot.keys.count,
            usesFloatComponents: false,
            componentsPerVector: 4,
            bytesPerComponent: 1,
            dataOffset: 0,
            dataStride: 4
        )
        let indices = (0..<snapshot.keys.count).map(UInt32.init)
        let indexData = indices.withUnsafeBufferPointer {
            Data(buffer: $0)
        }
        let element = SCNGeometryElement(
            data: indexData,
            primitiveType: .point,
            primitiveCount: snapshot.keys.count,
            bytesPerIndex: MemoryLayout<UInt32>.size
        )
        element.pointSize = snapshot.pointSizePixels
        element.minimumPointScreenSpaceRadius =
            snapshot.pointSizePixels / 2
        element.maximumPointScreenSpaceRadius =
            snapshot.pointSizePixels
        return configuredGeometry(
            sources: [vertices, colors],
            element: element
        )
    }

    private func cubeGeometry(
        _ snapshot: VisibilityGridRenderSnapshot
    ) -> SCNGeometry {
        let half =
            Float(
                snapshot.voxelSizeMeters *
                snapshot.cubeSizeFactor /
                2
            )
        let corners: [(Float, Float, Float)] = [
            (-half, -half, -half),
            ( half, -half, -half),
            ( half,  half, -half),
            (-half,  half, -half),
            (-half, -half,  half),
            ( half, -half,  half),
            ( half,  half,  half),
            (-half,  half,  half)
        ]
        let localIndices: [UInt32] = [
            0, 1, 2, 0, 2, 3,
            4, 6, 5, 4, 7, 6,
            0, 4, 5, 0, 5, 1,
            3, 2, 6, 3, 6, 7,
            0, 3, 7, 0, 7, 4,
            1, 5, 6, 1, 6, 2
        ]
        var vertices: [Float] = []
        var colors: [UInt32] = []
        var indices: [UInt32] = []
        vertices.reserveCapacity(snapshot.keys.count * 8 * 3)
        colors.reserveCapacity(snapshot.keys.count * 8)
        indices.reserveCapacity(snapshot.keys.count * 36)
        for row in snapshot.keys.indices {
            let centerOffset = row * 3
            for corner in corners {
                vertices.append(
                    snapshot.positionsWorld[centerOffset] + corner.0
                )
                vertices.append(
                    snapshot.positionsWorld[centerOffset + 1] + corner.1
                )
                vertices.append(
                    snapshot.positionsWorld[centerOffset + 2] + corner.2
                )
                colors.append(snapshot.colorsARGB[row])
            }
            let base = UInt32(row * 8)
            indices.append(contentsOf: localIndices.map { $0 + base })
        }
        let vertexData = vertices.withUnsafeBufferPointer {
            Data(buffer: $0)
        }
        let vertexSource = SCNGeometrySource(
            data: vertexData,
            semantic: .vertex,
            vectorCount: snapshot.keys.count * 8,
            usesFloatComponents: true,
            componentsPerVector: 3,
            bytesPerComponent: MemoryLayout<Float>.size,
            dataOffset: 0,
            dataStride: MemoryLayout<Float>.size * 3
        )
        let colorSource = SCNGeometrySource(
            data: Data(rgbaBytes(colors)),
            semantic: .color,
            vectorCount: colors.count,
            usesFloatComponents: false,
            componentsPerVector: 4,
            bytesPerComponent: 1,
            dataOffset: 0,
            dataStride: 4
        )
        let indexData = indices.withUnsafeBufferPointer {
            Data(buffer: $0)
        }
        let element = SCNGeometryElement(
            data: indexData,
            primitiveType: .triangles,
            primitiveCount: indices.count / 3,
            bytesPerIndex: MemoryLayout<UInt32>.size
        )
        return configuredGeometry(
            sources: [vertexSource, colorSource],
            element: element
        )
    }

    private func configuredGeometry(
        sources: [SCNGeometrySource],
        element: SCNGeometryElement
    ) -> SCNGeometry {
        let geometry = SCNGeometry(
            sources: sources,
            elements: [element]
        )
        let material = SCNMaterial()
        material.lightingModel = .constant
        material.isDoubleSided = true
        material.readsFromDepthBuffer = true
        material.writesToDepthBuffer = true
        geometry.materials = [material]
        return geometry
    }

    private func rgbaBytes(_ colors: [UInt32]) -> [UInt8] {
        var bytes: [UInt8] = []
        bytes.reserveCapacity(colors.count * 4)
        for color in colors {
            bytes.append(UInt8((color >> 16) & 0xff))
            bytes.append(UInt8((color >> 8) & 0xff))
            bytes.append(UInt8(color & 0xff))
            bytes.append(UInt8((color >> 24) & 0xff))
        }
        return bytes
    }
}
