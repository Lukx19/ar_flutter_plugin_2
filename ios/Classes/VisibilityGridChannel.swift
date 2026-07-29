import ARKit
import Flutter
import Foundation
import SceneKit

private struct VisibilityGridObservationContext {
    let token: Int64
    let identity: VisibilityGridIdentity
}

private struct VisibilityGridFeatureWork {
    let context: VisibilityGridObservationContext
    let featureTimestampNanoseconds: Int64
    let features: [UnassociatedFeatureSample]?
    let sourceRejectedFeatures: Int
    let featureUnavailable: Bool
}

private struct VisibilityGridDepthWork {
    let context: VisibilityGridObservationContext
    let timestampNanoseconds: Int64
    let depth: SceneDepthAcquisition
}

private struct CopiedVisibilityGridFeatures {
    let timestampNanoseconds: Int64
    let samples: [UnassociatedFeatureSample]
    let rejectedSamples: Int
}

final class VisibilityGridChannel {
    private let channel: FlutterMethodChannel
    private let queue = DispatchQueue(
        label: "capture3d.visibility-grid",
        qos: .userInitiated
    )
    private let lock = NSLock()
    private let lifecycle = VisibilityGridLifecycleEpoch()
    private let sceneRenderer: VisibilityGridSceneRenderer
    private let sceneDepthSupported: Bool
    private var grid: NativeVisibilityGrid?
    private var renderer: VisibilityGridRendererState?
    private var associator: BoundedFeatureAssociator?
    private var group: VisibilityGridGroupConfiguration?
    private var depthConfiguration: VisibilityGridDepthConfiguration?
    private var sessionGeneration: Int64 = 0
    private var visibilityRevision: Int64 = 0
    private let sensorHandoff =
        LatestSensorHandoff<VisibilityGridFeatureWork, VisibilityGridDepthWork>()
    private var draining = false
    private var disposed = false
    private var paused = false
    private var checkpointActive = false
    private var maximumFeatures = 2_000
    private var minimumFeatureConfidence = 0.30
    private var lastEmittedHealth: [String: String]?
    private var lastEmittedGeometryRevision: Int64 = -1
    private var rendererHealth = "configured"
    private var coalescedFeatureObservations: Int64 = 0
    private var coalescedDepthObservations: Int64 = 0
    private var callbackCopySamples: [Int64] = []
    private var healthHeartbeatGeneration: Int64 = 0

    init(
        messenger: FlutterBinaryMessenger,
        viewId: Int64,
        rootNode: SCNNode
    ) {
        channel = FlutterMethodChannel(
            name: "arpointcloud_\(viewId)",
            binaryMessenger: messenger
        )
        sceneRenderer = VisibilityGridSceneRenderer(rootNode: rootNode)
        if #available(iOS 14.0, *) {
            sceneDepthSupported =
                ARWorldTrackingConfiguration.supportsFrameSemantics(
                    .sceneDepth
                )
        } else {
            sceneDepthSupported = false
        }
        channel.setMethodCallHandler(handle)
    }

    func onFrame(_ frame: ARFrame) {
        let callbackStarted = DispatchTime.now().uptimeNanoseconds
        lock.lock()
        guard !disposed, !paused, !checkpointActive,
            let activeGroup = group,
            let activeDepthConfiguration = depthConfiguration
        else {
            lock.unlock()
            return
        }
        let token = lifecycle.token
        let featureLimit = maximumFeatures
        let confidenceMinimum = minimumFeatureConfidence
        lock.unlock()

        let feature = copyFeatureObservation(
            frame: frame,
            limit: featureLimit,
            confidenceMinimum: confidenceMinimum
        )
        let depth: SceneDepthAcquisition
        if #available(iOS 14.0, *), sceneDepthSupported {
            depth = SceneDepthAdapter.acquire(
                frame: frame,
                groupGeneration: activeGroup.groupGeneration,
                sessionGeneration: activeGroup.sessionGeneration,
                configuration: activeDepthConfiguration
            )
        } else {
            depth = .transientlyUnavailable
        }
        let context = VisibilityGridObservationContext(
            token: token,
            identity: activeGroup.identity
        )
        let featureWork = VisibilityGridFeatureWork(
            context: context,
            featureTimestampNanoseconds:
                feature?.timestampNanoseconds ??
                max(0, Int64(frame.timestamp * 1_000_000_000)),
            features: feature?.samples,
            sourceRejectedFeatures: feature?.rejectedSamples ?? 0,
            featureUnavailable: frame.rawFeaturePoints == nil
        )
        let depthWork = VisibilityGridDepthWork(
            context: context,
            timestampNanoseconds:
                max(0, Int64(frame.timestamp * 1_000_000_000)),
            depth: depth
        )
        lock.lock()
        guard !disposed, !paused, !checkpointActive,
            lifecycle.allows(token),
            group?.identity == activeGroup.identity
        else {
            lock.unlock()
            return
        }
        let callbackElapsed =
            Int64(DispatchTime.now().uptimeNanoseconds - callbackStarted)
        callbackCopySamples.append(max(0, callbackElapsed))
        if callbackCopySamples.count > 256 {
            callbackCopySamples.removeFirst(
                callbackCopySamples.count - 256
            )
        }
        if sensorHandoff.offerFeature(featureWork) {
            coalescedFeatureObservations += 1
        }
        if sensorHandoff.offerDepth(depthWork) {
            coalescedDepthObservations += 1
        }
        if draining {
            lock.unlock()
            return
        }
        draining = true
        lock.unlock()
        queue.async { [weak self] in self?.drain() }
    }

    func pause() {
        queue.sync {
            lock.lock()
            guard !disposed else {
                lock.unlock()
                return
            }
            paused = true
            sensorHandoff.clear()
            checkpointActive = false
            lifecycle.pause()
            healthHeartbeatGeneration += 1
            lock.unlock()
        }
    }

    func resume() {
        queue.sync {
            lock.lock()
            guard !disposed else {
                lock.unlock()
                return
            }
            paused = false
            lifecycle.resume()
            lock.unlock()
        }
        restartHealthHeartbeat()
    }

    func resetSession() {
        var resetIdentity: VisibilityGridIdentity?
        var resetToken: Int64 = -1
        var resetSessionGeneration: Int64 = -1
        queue.sync {
            lock.lock()
            guard !disposed else {
                lock.unlock()
                return
            }
            resetIdentity = group?.identity
            sensorHandoff.clear()
            checkpointActive = false
            healthHeartbeatGeneration += 1
            lifecycle.resetSession()
            resetToken = lifecycle.token
            sessionGeneration += 1
            resetSessionGeneration = sessionGeneration
            group = nil
            lock.unlock()
            grid?.stopGroup()
            associator?.reset()
            renderer?.stopGroup()
            lastEmittedGeometryRevision = -1
            publishRenderer()
        }
        guard let resetIdentity else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.lock.lock()
            let valid =
                self.lifecycle.allows(resetToken) &&
                self.group?.identity == nil &&
                !self.disposed
            self.lock.unlock()
            guard valid else { return }
            self.channel.invokeMethod(
                "onError",
                arguments: [
                    "code": "VG_SESSION_MISMATCH",
                    "message":
                        "AR session reset invalidated the active grid",
                    "recoverable": true,
                    "fatalToFeature": false,
                    "fatalToDepth": false,
                    "fatalToRenderer": false,
                    "fatalToGrid": true,
                    "groupGeneration":
                        resetIdentity.groupGeneration,
                    "sessionGeneration":
                        resetSessionGeneration
                ]
            )
        }
    }

    func dispose() {
        var didDispose = false
        queue.sync {
            lock.lock()
            if !disposed {
                disposed = true
                sensorHandoff.clear()
                lifecycle.dispose()
                healthHeartbeatGeneration += 1
                group = nil
                didDispose = true
            }
            lock.unlock()
            guard didDispose else { return }
            grid?.stopGroup()
            grid = nil
            renderer?.dispose()
            renderer = nil
            associator = nil
        }
        guard didDispose else { return }
        channel.setMethodCallHandler(nil)
        DispatchQueue.main.async { self.sceneRenderer.dispose() }
    }

    private func handle(
        call: FlutterMethodCall,
        result: @escaping FlutterResult
    ) {
        if call.method == "dispose" {
            dispose()
            result(true)
            return
        }
        lock.lock()
        let admissionToken = lifecycle.token
        let admitted = !disposed && lifecycle.allows(admissionToken)
        lock.unlock()
        guard admitted else {
            result(error("VG_NOT_INITIALIZED", "Visibility grid is disposed"))
            return
        }
        queue.async { [weak self] in
            guard let self else { return }
            self.lock.lock()
            let stillAdmitted =
                self.lifecycle.allows(admissionToken) && !self.disposed
            self.lock.unlock()
            guard stillAdmitted else {
                DispatchQueue.main.async {
                    result(
                        self.error(
                            "VG_NOT_INITIALIZED",
                            "Visibility lifecycle changed before call"
                        )
                    )
                }
                return
            }
            do {
                let value = try self.process(call)
                self.lock.lock()
                let completionToken = self.lifecycle.token
                self.lock.unlock()
                DispatchQueue.main.async {
                    self.lock.lock()
                    let valid =
                        self.lifecycle.allows(completionToken) &&
                        !self.disposed
                    self.lock.unlock()
                    if valid {
                        result(value)
                    } else {
                        result(
                            self.error(
                                "VG_NOT_INITIALIZED",
                                "Visibility lifecycle changed during call"
                            )
                        )
                    }
                }
            } catch {
                self.lock.lock()
                let completionToken = self.lifecycle.token
                self.lock.unlock()
                DispatchQueue.main.async {
                    self.lock.lock()
                    let valid =
                        self.lifecycle.allows(completionToken) &&
                        !self.disposed
                    self.lock.unlock()
                    result(
                        valid
                            ? self.flutterError(error)
                            : self.error(
                                "VG_NOT_INITIALIZED",
                                "Visibility lifecycle changed during call"
                            )
                    )
                }
            }
        }
    }

    private func process(_ call: FlutterMethodCall) throws -> Any? {
        switch call.method {
        case "init":
            return try initialize(arguments(call))
        case "startGrid":
            return try startGrid(arguments(call))
        case "ackGeometry":
            let values = arguments(call)
            let identity = try requireIdentity(values)
            let accepted = try requiredInt64(
                values,
                "acceptedGeometryRevision"
            )
            return [
                "accepted":
                    try requireGrid().acknowledgeGeometry(
                        identity: identity,
                        acceptedGeometryRevision: accepted
                    )
            ]
        case "requestSnapshot":
            return try requestSnapshot(arguments(call))
        case "getHealth":
            guard let payload = healthWireMap() else {
                throw VisibilityGridContractError.notInitialized
            }
            return payload
        case "applyVisibility":
            return try applyVisibility(arguments(call))
        case "checkpointBarrier":
            return try checkpoint(arguments(call))
        case "releaseCheckpoint":
            _ = try requireIdentity(arguments(call))
            let values = arguments(call)
            let expectedGeometry =
                try requiredInt64(values, "geometryRevision")
            let expectedVisibility =
                try requiredInt64(values, "visibilityRevision")
            let activeRenderer = try requireRenderer()
            lock.lock()
            let wasActive = checkpointActive
            checkpointActive = false
            lock.unlock()
            return [
                "released":
                    wasActive &&
                    activeRenderer.geometryRevision == expectedGeometry &&
                    activeRenderer.visibilityRevision == expectedVisibility
            ]
        case "setPointsEnabled":
            let values = arguments(call)
            guard let enabled = values["enabled"] as? Bool else {
                throw VisibilityGridContractError.invalidArgument(
                    "enabled is required"
                )
            }
            let activeRenderer = try requireRenderer()
            activeRenderer.enabled = enabled
            publishRenderer()
            return true
        case "setVoxelRenderMode":
            let mode = try requiredString(arguments(call), "mode")
            guard let parsed = VisibilityGridRenderMode(rawValue: mode) else {
                throw VisibilityGridContractError.invalidArgument(
                    "Invalid voxel render mode"
                )
            }
            let activeRenderer = try requireRenderer()
            activeRenderer.mode = parsed
            publishRenderer()
            return true
        case "stopGrid":
            _ = try requireIdentity(arguments(call))
            lock.lock()
            sensorHandoff.clear()
            checkpointActive = false
            healthHeartbeatGeneration += 1
            lifecycle.changeGroup()
            group = nil
            lock.unlock()
            try requireGrid().stopGroup()
            try requireRenderer().stopGroup()
            associator?.reset()
            lastEmittedGeometryRevision = -1
            publishRenderer(nil)
            return true
        default:
            return FlutterMethodNotImplemented
        }
    }

    private func initialize(_ values: [String: Any]) throws -> [String: Any] {
        guard try requiredString(values, "version") ==
                visibilityGridWireVersion
        else {
            throw VisibilityGridContractError.versionMismatch
        }
        if values["syntheticSource"] as? Bool == true &&
            !syntheticInputAllowed {
            throw VisibilityGridContractError.syntheticForbidden
        }
        let requestedFeatureCapacity =
            try requiredInt(
                values,
                "featureTrackCapacity",
                1,
                200_000
            )
        let acceptedFeatureCapacity =
            BoundedFeatureAssociator.acceptedCapacity(
                for: requestedFeatureCapacity
            )
        let requestedRenderCapacity =
            try requiredInt(values, "renderCapacity", 1, 100_000)
        let acceptedRenderCapacity = min(
            requestedRenderCapacity,
            VisibilityGridRendererState.maximumWorstCaseCapacity
        )
        let featureConfiguration =
            try VisibilityGridFeatureConfiguration(
                stableVoxelCapacity: acceptedRenderCapacity,
                featureTrackCapacity: acceptedFeatureCapacity,
                maxFeaturesPerObservation:
                    try requiredInt(
                        values,
                        "maxFeaturesPerObservation",
                        1,
                        2_000
                    ),
                publishIntervalMilliseconds:
                    try requiredInt(
                        values,
                        "publishIntervalMs",
                        500,
                        Int.max
                    ),
                minimumConfidence:
                    try requiredDouble(
                        values,
                        "featureConfidenceMinimum",
                        0,
                        1
                    )
            )
        let depth = try VisibilityGridDepthConfiguration(
            minimumConfidence:
                UInt8(
                    try requiredInt(
                        values,
                        "depthConfidenceMinimum",
                        0,
                        255
                    )
                ),
            maxAcceptedPixelsPerObservation:
                try requiredInt(
                    values,
                    "maxDepthPixelsPerObservation",
                    1,
                    4_096
                ),
            maxRayVisitsPerObservation:
                try requiredInt(
                    values,
                    "maxRayVisitsPerObservation",
                    1,
                    65_536
                )
        )
        let nextRenderer = try VisibilityGridRendererState(
            capacity: featureConfiguration.stableVoxelCapacity,
            defaultColor: UInt32(
                bitPattern:
                    try requiredInt32Color(values, "defaultColor")
            )
        )
        nextRenderer.pointSizePixels =
            CGFloat(
                try requiredDouble(
                    values,
                    "pointSizePx",
                    Double.leastNonzeroMagnitude,
                    Double.greatestFiniteMagnitude
                )
            )
        nextRenderer.enabled = (values["enabled"] as? Bool) ?? true
        let renderMode = try requiredString(values, "voxelRenderMode")
        guard let parsedMode =
            VisibilityGridRenderMode(rawValue: renderMode)
        else {
            throw VisibilityGridContractError.invalidArgument(
                "Invalid voxel render mode"
            )
        }
        nextRenderer.mode = parsedMode
        nextRenderer.cubeSizeFactor =
            try requiredDouble(values, "cubeSizeFactor", 0.1, 1)

        grid?.stopGroup()
        renderer?.dispose()
        grid = try NativeVisibilityGrid(
            featureConfiguration: featureConfiguration,
            depthConfiguration: sceneDepthSupported ? depth : nil
        )
        renderer = nextRenderer
        associator = try BoundedFeatureAssociator(
            capacity: featureConfiguration.featureTrackCapacity
        )
        visibilityRevision = 0
        lastEmittedGeometryRevision = -1
        rendererHealth = "healthy"
        lock.lock()
        sessionGeneration += 1
        let initializedSessionGeneration = sessionGeneration
        depthConfiguration = depth
        maximumFeatures = featureConfiguration.maxFeaturesPerObservation
        minimumFeatureConfidence = featureConfiguration.minimumConfidence
        sensorHandoff.clear()
        coalescedFeatureObservations = 0
        coalescedDepthObservations = 0
        callbackCopySamples.removeAll(keepingCapacity: true)
        checkpointActive = false
        healthHeartbeatGeneration += 1
        lifecycle.resetSession()
        group = nil
        lock.unlock()
        publishRenderer(nil)
        let depthHealth = sceneDepthSupported ? "configured" : "unsupported"
        lastEmittedHealth = [
            "feature": "configured",
            "depth": depthHealth,
            "renderer": "healthy",
            "totalGrid": "healthy"
        ]
        var diagnostics = VisibilityGridDiagnostics()
        diagnostics.featureTrackCapacity =
            featureConfiguration.featureTrackCapacity
        diagnostics.stableVoxelCapacity =
            featureConfiguration.stableVoxelCapacity
        diagnostics.rendererFreeRows =
            featureConfiguration.stableVoxelCapacity
        diagnostics.estimatedStateBytes = 8_192
        return [
            "version": visibilityGridWireVersion,
            "sessionGeneration": initializedSessionGeneration,
            "rendererReady": true,
            "featureReady": true,
            "depthCapability":
                sceneDepthSupported ? "sceneDepth" : "unsupported",
            "depthConfigured": sceneDepthSupported,
            "depthActiveMode":
                sceneDepthSupported ? "sceneDepth" : "featureOnly",
            "renderCapacity": featureConfiguration.stableVoxelCapacity,
            "featureTrackCapacity":
                featureConfiguration.featureTrackCapacity,
            "health": lastEmittedHealth!,
            "diagnostics": diagnostics.wireMap()
        ]
    }

    private func startGrid(_ values: [String: Any]) throws -> [String: Any] {
        guard try requiredString(values, "version") ==
                visibilityGridWireVersion
        else {
            throw VisibilityGridContractError.versionMismatch
        }
        guard try requiredString(values, "groupFrameConvention") ==
                "gravity_y_up_meters_v1",
            try requiredString(values, "matrixConvention") ==
                "column_major_gl_v1"
        else {
            throw VisibilityGridContractError.invalidArgument(
                "Unsupported visibility-grid coordinate convention"
            )
        }
        let acceptedGroupCapacity = min(
            try requiredInt(values, "capacity", 1, 100_000),
            try requireRenderer().capacity
        )
        let next = try VisibilityGridGroupConfiguration(
            groupId: try requiredString(values, "groupId"),
            groupGeneration:
                try requiredInt64(values, "groupGeneration"),
            sessionGeneration: sessionGeneration,
            voxelSizeMeters:
                try requiredDouble(
                    values,
                    "voxelSizeMeters",
                    Double.leastNonzeroMagnitude,
                    Double.greatestFiniteMagnitude
                ),
            capacity: acceptedGroupCapacity,
            groupFromWorldGL:
                try requiredFloat64List(values, "groupFromWorldGl"),
            worldFromGroupGL:
                try requiredFloat64List(values, "worldFromGroupGl"),
            restoredGeometryRevision:
                try requiredInt64(values, "restoredGeometryRevision"),
            restoredVisibilityRevision:
                try requiredInt64(values, "restoredVisibilityRevision"),
            restoredKeys: try requiredInt64List(values, "restoredKeys")
                .map { UInt64(bitPattern: $0) }
        )
        let activeGrid = try requireGrid()
        associator?.reset()
        _ = try activeGrid.startGroup(next)
        try requireRenderer().startGroup(
            voxelSizeMeters: next.voxelSizeMeters,
            worldFromGroupGL: next.worldFromGroupGL,
            geometryRevision: next.restoredGeometryRevision,
            visibilityRevision: next.restoredVisibilityRevision,
            restoredKeys: next.restoredKeys
        )
        visibilityRevision = next.restoredVisibilityRevision
        lock.lock()
        sensorHandoff.clear()
        checkpointActive = false
        lifecycle.changeGroup()
        group = next
        coalescedFeatureObservations = 0
        coalescedDepthObservations = 0
        callbackCopySamples.removeAll(keepingCapacity: true)
        lock.unlock()
        guard let delta = activeGrid.requestSnapshot(
            identity: next.identity,
            receiverGeometryRevision: next.restoredGeometryRevision,
            nowNanoseconds: nowNanoseconds()
        ) else {
            throw VisibilityGridContractError.staleIdentity
        }
        guard try requireRenderer().applyGeometry(
            revision: delta.geometryRevision,
            reset: true,
            upsertKeys: delta.upsertKeys,
            removalKeys: delta.removalKeys
        ) else {
            throw VisibilityGridContractError.staleRevision
        }
        rendererHealth = "healthy"
        lastEmittedGeometryRevision = delta.geometryRevision
        publishRenderer()
        restartHealthHeartbeat()
        return deltaWireMap(delta)
    }

    private func requestSnapshot(
        _ values: [String: Any]
    ) throws -> [String: Any] {
        let identity = try requireIdentity(values)
        guard let delta = try requireGrid().requestSnapshot(
            identity: identity,
            receiverGeometryRevision:
                try requiredInt64(values, "receiverGeometryRevision"),
            nowNanoseconds: nowNanoseconds()
        ) else {
            throw VisibilityGridContractError.staleIdentity
        }
        guard try requireRenderer().applyGeometry(
            revision: delta.geometryRevision,
            reset: true,
            upsertKeys: delta.upsertKeys,
            removalKeys: delta.removalKeys
        ) else {
            throw VisibilityGridContractError.staleRevision
        }
        rendererHealth = "healthy"
        lastEmittedGeometryRevision = delta.geometryRevision
        publishRenderer()
        return deltaWireMap(delta)
    }

    private func applyVisibility(
        _ values: [String: Any]
    ) throws -> [String: Any] {
        _ = try requireIdentity(values)
        let geometryRevision =
            try requiredInt64(values, "geometryRevision")
        let nextVisibilityRevision =
            try requiredInt64(values, "visibilityRevision")
        let keys = try requiredInt64List(values, "keys")
            .map { UInt64(bitPattern: $0) }
        let colors = try requiredInt32List(values, "colors")
            .map { UInt32(bitPattern: $0) }
        guard keys.count == colors.count,
            Set(keys).count == keys.count
        else {
            throw VisibilityGridContractError.invalidArgument(
                "Visibility keys and colors must be unique parallel lists"
            )
        }
        let activeRenderer = try requireRenderer()
        guard geometryRevision == activeRenderer.geometryRevision else {
            throw VisibilityGridContractError.staleRevision
        }
        guard nextVisibilityRevision > visibilityRevision else {
            throw VisibilityGridContractError.staleVisibility
        }
        guard
            activeRenderer.applyVisibility(
                geometryRevision: geometryRevision,
                visibilityRevision: nextVisibilityRevision,
                keys: keys,
                colors: colors
            )
        else {
            throw VisibilityGridContractError.invalidArgument(
                "Renderer rejected visibility patch"
            )
        }
        visibilityRevision = nextVisibilityRevision
        publishRenderer()
        return [
            "applied": true,
            "geometryRevision": geometryRevision,
            "visibilityRevision": visibilityRevision
        ]
    }

    private func checkpoint(
        _ values: [String: Any]
    ) throws -> [String: Any] {
        let identity = try requireIdentity(values)
        lock.lock()
        guard !checkpointActive else {
            lock.unlock()
            throw VisibilityGridContractError.invalidArgument(
                "Checkpoint barrier is already active"
            )
        }
        checkpointActive = true
        sensorHandoff.clear()
        lock.unlock()
        guard let delta = try requireGrid().requestSnapshot(
            identity: identity,
            receiverGeometryRevision:
                try requiredInt64(values, "receiverGeometryRevision"),
            nowNanoseconds: nowNanoseconds()
        ) else {
            lock.lock()
            checkpointActive = false
            lock.unlock()
            throw VisibilityGridContractError.staleIdentity
        }
        guard try requireRenderer().applyGeometry(
            revision: delta.geometryRevision,
            reset: true,
            upsertKeys: delta.upsertKeys,
            removalKeys: delta.removalKeys
        ) else {
            lock.lock()
            checkpointActive = false
            lock.unlock()
            throw VisibilityGridContractError.staleRevision
        }
        rendererHealth = "healthy"
        lastEmittedGeometryRevision = delta.geometryRevision
        publishRenderer()
        return deltaWireMap(delta)
    }

    private func drain() {
        while true {
            lock.lock()
            guard let work = sensorHandoff.take() else {
                draining = false
                lock.unlock()
                return
            }
            lock.unlock()
            guard let activeGrid = grid else { continue }
            do {
                for source in sensorProcessingOrder(
                    featureTimestampNanoseconds:
                        work.feature?.featureTimestampNanoseconds,
                    depthTimestampNanoseconds:
                        work.depth?.timestampNanoseconds
                ) {
                    switch source {
                    case .feature:
                        try consumeFeature(
                            work.feature!,
                            using: activeGrid
                        )
                    case .depth:
                        try consumeDepth(
                            work.depth!,
                            using: activeGrid
                        )
                    }
                }
                guard let context =
                    work.depth?.context ?? work.feature?.context,
                    isCurrent(context)
                else { continue }
                let health = activeGrid.snapshot().diagnostics.health(
                    renderer: rendererHealth
                )
                if health != lastEmittedHealth {
                    lastEmittedHealth = health
                    let healthPayload = healthWireMap()
                    DispatchQueue.main.async { [weak self] in
                        guard let self, self.isCurrent(context) else {
                            return
                        }
                        self.channel.invokeMethod(
                            "onGridHealth",
                            arguments: healthPayload
                        )
                    }
                }
                if let delta = activeGrid.takeGeometryDelta(
                    nowNanoseconds: nowNanoseconds()
                ) {
                    guard delta.geometryRevision !=
                        lastEmittedGeometryRevision
                    else {
                        continue
                    }
                    let rendererApplied = renderer?.applyGeometry(
                        revision: delta.geometryRevision,
                        reset: delta.reset,
                        upsertKeys: delta.upsertKeys,
                        removalKeys: delta.removalKeys
                    ) == true
                    lastEmittedGeometryRevision = delta.geometryRevision
                    if rendererApplied {
                        rendererHealth = "healthy"
                    } else {
                        rendererHealth = "failed"
                        emitError(
                            code: "VG_RENDERER_FAILED",
                            message: "Renderer rejected geometry revision"
                        )
                        let failedHealth =
                            activeGrid.snapshot().diagnostics.health(
                                renderer: rendererHealth
                            )
                        if failedHealth != lastEmittedHealth {
                            lastEmittedHealth = failedHealth
                            let healthPayload = healthWireMap()
                            DispatchQueue.main.async { [weak self] in
                                guard let self,
                                    self.isCurrent(context)
                                else {
                                    return
                                }
                                self.channel.invokeMethod(
                                    "onGridHealth",
                                    arguments: healthPayload
                                )
                            }
                        }
                    }
                    let renderSnapshot =
                        rendererApplied ? renderer?.snapshot() : nil
                    let wirePayload = deltaWireMap(delta)
                    DispatchQueue.main.async { [weak self] in
                        guard let self, self.isCurrent(context) else {
                            return
                        }
                        if rendererApplied {
                            self.sceneRenderer.render(renderSnapshot)
                        }
                        self.channel.invokeMethod(
                            "onGridDelta",
                            arguments: wirePayload
                        )
                    }
                }
            } catch {
                emitError(
                    code: "VG_INTERNAL",
                    message: String(describing: error)
                )
            }
        }
    }

    private func consumeFeature(
        _ work: VisibilityGridFeatureWork,
        using grid: NativeVisibilityGrid
    ) throws {
        guard isCurrent(work.context) else { return }
        if let features = work.features,
            let associator {
            let associated = associator.associate(
                timestampNanoseconds: work.featureTimestampNanoseconds,
                samples: features
            )
            try grid.observeFeatures(
                FeatureObservation(
                    timestampNanoseconds:
                        work.featureTimestampNanoseconds,
                    groupGeneration:
                        work.context.identity.groupGeneration,
                    sessionGeneration:
                        work.context.identity.sessionGeneration,
                    samples: associated.samples,
                    sourceRejectedSamples:
                        work.sourceRejectedFeatures +
                        associated.rejectedSamples
                )
            )
        } else if work.featureUnavailable {
            grid.reportFeatureTransientUnavailable()
        }
    }

    private func consumeDepth(
        _ work: VisibilityGridDepthWork,
        using grid: NativeVisibilityGrid
    ) throws {
        guard isCurrent(work.context) else { return }
        switch work.depth {
        case .observation(let observation):
            _ = try grid.observeDepth(observation)
        case .transientlyUnavailable:
            grid.reportDepthTransientUnavailable()
        case .failure(let reason):
            let previousDepthHealth =
                grid.snapshot().diagnostics.depthHealth
            grid.reportDepthFailure()
            let currentDepthHealth =
                grid.snapshot().diagnostics.depthHealth
            if previousDepthHealth != "failed" &&
                currentDepthHealth == "failed" {
                emitError(
                    code: "VG_DEPTH_FAILED",
                    message: "\(reason); continuing feature-only"
                )
            }
        }
    }

    private func copyFeatureObservation(
        frame: ARFrame,
        limit: Int,
        confidenceMinimum: Double
    ) -> CopiedVisibilityGridFeatures? {
        guard let cloud = frame.rawFeaturePoints else { return nil }
        let acceptedCount = min(limit, cloud.points.count)
        var samples: [UnassociatedFeatureSample] = []
        samples.reserveCapacity(acceptedCount)
        for index in 0..<acceptedCount {
            let point = cloud.points[index]
            let confidence = 1.0
            guard confidence >= confidenceMinimum else { continue }
            samples.append(
                UnassociatedFeatureSample(
                    world: VisibilityGridPoint(
                        x: Double(point.x),
                        y: Double(point.y),
                        z: Double(point.z)
                    ),
                    confidence: confidence
                )
            )
        }
        return CopiedVisibilityGridFeatures(
            timestampNanoseconds:
                max(0, Int64(frame.timestamp * 1_000_000_000)),
            samples: samples,
            rejectedSamples:
                max(0, cloud.points.count - acceptedCount)
        )
    }

    private func isCurrent(
        _ work: VisibilityGridObservationContext
    ) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return !disposed &&
            lifecycle.allows(work.token) &&
            group?.identity == work.identity
    }

    private func publishRenderer(
        _ explicit: VisibilityGridRenderSnapshot? = nil
    ) {
        let snapshot = explicit ?? renderer?.snapshot()
        lock.lock()
        let token = lifecycle.token
        lock.unlock()
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.lock.lock()
            let valid = self.lifecycle.allows(token) && !self.disposed
            self.lock.unlock()
            if valid {
                self.sceneRenderer.render(snapshot)
            }
        }
    }

    private func restartHealthHeartbeat() {
        lock.lock()
        healthHeartbeatGeneration += 1
        let generation = healthHeartbeatGeneration
        let shouldSchedule =
            !disposed && !paused && group != nil
        lock.unlock()
        guard shouldSchedule else { return }
        scheduleHealthHeartbeat(generation)
    }

    private func scheduleHealthHeartbeat(_ generation: Int64) {
        DispatchQueue.main.asyncAfter(
            deadline: .now() + .seconds(1)
        ) { [weak self] in
            self?.queue.async { [weak self] in
                self?.emitHealthHeartbeat(generation)
            }
        }
    }

    private func emitHealthHeartbeat(_ generation: Int64) {
        lock.lock()
        let valid =
            !disposed &&
            !paused &&
            group != nil &&
            generation == healthHeartbeatGeneration
        lock.unlock()
        guard valid, let payload = healthWireMap() else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.lock.lock()
            let stillValid =
                !self.disposed &&
                !self.paused &&
                self.group != nil &&
                generation == self.healthHeartbeatGeneration
            self.lock.unlock()
            guard stillValid else { return }
            self.channel.invokeMethod(
                "onGridHealth",
                arguments: payload
            )
        }
        scheduleHealthHeartbeat(generation)
    }

    private func enrichedDiagnostics(
        _ diagnostics: VisibilityGridDiagnostics
    ) -> VisibilityGridDiagnostics {
        lock.lock()
        let featureCoalescing = coalescedFeatureObservations
        let depthCoalescing = coalescedDepthObservations
        let sortedCallbackSamples = callbackCopySamples.sorted()
        lock.unlock()
        let callbackP95: Int64
        if sortedCallbackSamples.isEmpty {
            callbackP95 = 0
        } else {
            let index = min(
                sortedCallbackSamples.count - 1,
                max(
                    0,
                    (sortedCallbackSamples.count * 95 + 99) / 100 - 1
                )
            )
            callbackP95 = sortedCallbackSamples[index]
        }
        var enriched = diagnostics
        enriched.callbackCopyP95Nanoseconds = callbackP95
        enriched.coalescedFeatureObservations = featureCoalescing
        enriched.coalescedDepthObservations = depthCoalescing
        let freeRows = renderer?.freeRowCount ??
            diagnostics.stableVoxelCapacity
        enriched.rendererRows =
            (renderer?.capacity ?? diagnostics.stableVoxelCapacity) - freeRows
        enriched.rendererFreeRows = freeRows
        return enriched
    }

    private func healthWireMap() -> [String: Any]? {
        guard group != nil, let grid else { return nil }
        let diagnostics = enrichedDiagnostics(
            grid.snapshot().diagnostics
        )
        return [
            "version": visibilityGridWireVersion,
            "sourceHealth":
                diagnostics.health(renderer: rendererHealth),
            "diagnostics": diagnostics.wireMap()
        ]
    }

    private func deltaWireMap(
        _ delta: VisibilityGridDelta
    ) -> [String: Any] {
        let freeRows = renderer?.freeRowCount ?? delta.capacity
        let enriched = enrichedDiagnostics(delta.diagnostics)
        return VisibilityGridDelta(
            identity: delta.identity,
            baseGeometryRevision: delta.baseGeometryRevision,
            geometryRevision: delta.geometryRevision,
            reset: delta.reset,
            upsertKeys: delta.upsertKeys,
            removalKeys: delta.removalKeys,
            capacity: delta.capacity,
            diagnostics: enriched
        ).wireMap(
            rendererHealth: rendererHealth,
            rendererRows: delta.capacity - freeRows,
            rendererFreeRows: freeRows
        )
    }

    private func emitError(code: String, message: String) {
        lock.lock()
        let identity = group?.identity
        let token = lifecycle.token
        lock.unlock()
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.lock.lock()
            let valid = self.lifecycle.allows(token) && !self.disposed
            self.lock.unlock()
            guard valid else { return }
            self.channel.invokeMethod(
                "onError",
                arguments: [
                    "code": code,
                    "message": message,
                    "recoverable": true,
                    "fatalToFeature": false,
                    "fatalToDepth": code == "VG_DEPTH_FAILED",
                    "fatalToRenderer": code == "VG_RENDERER_FAILED",
                    "fatalToGrid": false,
                    "groupGeneration": identity?.groupGeneration as Any,
                    "sessionGeneration": identity?.sessionGeneration as Any
                ]
            )
        }
    }

    private func requireGrid() throws -> NativeVisibilityGrid {
        guard let grid else {
            throw VisibilityGridContractError.notInitialized
        }
        return grid
    }

    private func requireRenderer() throws -> VisibilityGridRendererState {
        guard let renderer else {
            throw VisibilityGridContractError.notInitialized
        }
        return renderer
    }

    private func requireIdentity(
        _ values: [String: Any]
    ) throws -> VisibilityGridIdentity {
        guard try requiredString(values, "version") ==
                visibilityGridWireVersion
        else {
            throw VisibilityGridContractError.versionMismatch
        }
        lock.lock()
        let active = group
        lock.unlock()
        guard let active else {
            throw VisibilityGridContractError.notInitialized
        }
        let candidate = VisibilityGridIdentity(
            groupId: try requiredString(values, "groupId"),
            groupGeneration:
                try requiredInt64(values, "groupGeneration"),
            sessionGeneration:
                try requiredInt64(values, "sessionGeneration")
        )
        guard candidate.groupId == active.groupId,
            candidate.groupGeneration == active.groupGeneration
        else {
            throw VisibilityGridContractError.staleGroup
        }
        guard candidate.sessionGeneration == active.sessionGeneration else {
            throw VisibilityGridContractError.staleSession
        }
        return candidate
    }

    private func arguments(_ call: FlutterMethodCall) throws -> [String: Any] {
        guard let values = call.arguments as? [String: Any] else {
            throw VisibilityGridContractError.invalidArgument(
                "Method arguments are required"
            )
        }
        return values
    }

    private func flutterError(_ source: Error) -> FlutterError {
        switch source {
        case VisibilityGridContractError.versionMismatch:
            return error(
                "VG_VERSION_MISMATCH",
                "Unsupported visibility-grid wire version"
            )
        case VisibilityGridContractError.staleIdentity:
            return error("VG_GROUP_MISMATCH", "Stale visibility identity")
        case VisibilityGridContractError.staleGroup:
            return error("VG_GROUP_MISMATCH", "Stale visibility group")
        case VisibilityGridContractError.staleSession:
            return error("VG_SESSION_MISMATCH", "Stale visibility session")
        case VisibilityGridContractError.staleRevision:
            return error(
                "VG_GEOMETRY_REVISION_GAP",
                "Stale visibility revision"
            )
        case VisibilityGridContractError.staleVisibility:
            return error(
                "VG_VISIBILITY_REVISION_STALE",
                "Stale visibility color revision"
            )
        case VisibilityGridContractError.syntheticForbidden:
            return error(
                "VG_SYNTHETIC_FORBIDDEN",
                "Synthetic visibility input is debug-only"
            )
        case VisibilityGridContractError.notInitialized,
            VisibilityGridContractError.disposed:
            return error(
                "VG_NOT_INITIALIZED",
                "Visibility grid is not initialized"
            )
        case VisibilityGridContractError.capacityExceeded:
            return error(
                "VG_CAPACITY_REACHED",
                "Visibility grid capacity reached"
            )
        default:
            return error("VG_PROTOCOL_INVALID", String(describing: source))
        }
    }

    private func error(_ code: String, _ message: String) -> FlutterError {
        lock.lock()
        let identity = group?.identity
        let currentSessionGeneration = sessionGeneration
        lock.unlock()
        let nonRecoverableCodes: Set<String> = [
            "VG_VERSION_MISMATCH",
            "VG_SYNTHETIC_FORBIDDEN",
            "VG_PROTOCOL_INVALID"
        ]
        let fatalGridCodes: Set<String> = [
            "VG_VERSION_MISMATCH",
            "VG_SESSION_MISMATCH",
            "VG_NOT_INITIALIZED"
        ]
        return FlutterError(
            code: code,
            message: message,
            details: [
                "code": code,
                "message": message,
                "recoverable": !nonRecoverableCodes.contains(code),
                "fatalToFeature": false,
                "fatalToDepth": false,
                "fatalToRenderer": code == "VG_RENDERER_FAILED",
                "fatalToGrid": fatalGridCodes.contains(code),
                "groupGeneration":
                    identity?.groupGeneration as Any,
                "sessionGeneration":
                    identity?.sessionGeneration ??
                    currentSessionGeneration
            ]
        )
    }
}

private extension VisibilityGridDelta {
    func wireMap(
        rendererHealth: String,
        rendererRows: Int? = nil,
        rendererFreeRows: Int? = nil
    ) -> [String: Any] {
        var wireDiagnostics = diagnostics
        if let rendererRows, let rendererFreeRows {
            wireDiagnostics.rendererRows = rendererRows
            wireDiagnostics.rendererFreeRows = rendererFreeRows
        }
        return [
            "version": visibilityGridWireVersion,
            "groupId": identity.groupId,
            "groupGeneration": identity.groupGeneration,
            "sessionGeneration": identity.sessionGeneration,
            "baseGeometryRevision": baseGeometryRevision,
            "geometryRevision": geometryRevision,
            "reset": reset,
            "upsertKeys": visibilityGridInt64Data(upsertKeys),
            "removalKeys": visibilityGridInt64Data(removalKeys),
            "capacity": capacity,
            "sourceHealth": diagnostics.health(renderer: rendererHealth),
            "diagnostics": wireDiagnostics.wireMap()
        ]
    }
}

private func visibilityGridInt64Data(
    _ values: [UInt64]
) -> FlutterStandardTypedData {
    let signed = values.map { Int64(bitPattern: $0) }
    guard !signed.isEmpty else {
        return FlutterStandardTypedData(int64: Data())
    }
    return signed.withUnsafeBufferPointer {
        FlutterStandardTypedData(
            int64: Data(
                bytes: $0.baseAddress!,
                count: $0.count * MemoryLayout<Int64>.stride
            )
        )
    }
}

private func requiredString(
    _ values: [String: Any],
    _ name: String
) throws -> String {
    guard let value = values[name] as? String, !value.isEmpty else {
        throw VisibilityGridContractError.invalidArgument(
            "\(name) is required"
        )
    }
    return value
}

private func requiredInt64(
    _ values: [String: Any],
    _ name: String
) throws -> Int64 {
    guard let number = values[name] as? NSNumber else {
        throw VisibilityGridContractError.invalidArgument(
            "\(name) is required"
        )
    }
    let value = number.int64Value
    guard value >= 0 else {
        throw VisibilityGridContractError.invalidArgument(
            "\(name) must be non-negative"
        )
    }
    return value
}

private func requiredInt(
    _ values: [String: Any],
    _ name: String,
    _ minimum: Int,
    _ maximum: Int
) throws -> Int {
    let value = Int(try requiredInt64(values, name))
    guard (minimum...maximum).contains(value) else {
        throw VisibilityGridContractError.invalidArgument(
            "\(name) is outside its bounded range"
        )
    }
    return value
}

private func requiredDouble(
    _ values: [String: Any],
    _ name: String,
    _ minimum: Double,
    _ maximum: Double
) throws -> Double {
    guard let number = values[name] as? NSNumber else {
        throw VisibilityGridContractError.invalidArgument(
            "\(name) is required"
        )
    }
    let value = number.doubleValue
    guard value.isFinite, (minimum...maximum).contains(value) else {
        throw VisibilityGridContractError.invalidArgument(
            "\(name) is outside its bounded range"
        )
    }
    return value
}

private func requiredInt32Color(
    _ values: [String: Any],
    _ name: String
) throws -> Int32 {
    guard let number = values[name] as? NSNumber else {
        throw VisibilityGridContractError.invalidArgument(
            "\(name) is required"
        )
    }
    let value = number.int64Value
    guard value >= Int64(Int32.min), value <= Int64(UInt32.max) else {
        throw VisibilityGridContractError.invalidArgument(
            "\(name) is outside the ARGB range"
        )
    }
    return Int32(bitPattern: UInt32(truncatingIfNeeded: value))
}

private func requiredFloat64List(
    _ values: [String: Any],
    _ name: String
) throws -> [Double] {
    guard let typed = values[name] as? FlutterStandardTypedData else {
        throw VisibilityGridContractError.invalidArgument(
            "\(name) must be Float64List"
        )
    }
    let result: [Double] = typed.data.withUnsafeBytes {
        Array($0.bindMemory(to: Double.self))
    }
    guard result.count == 16, result.allSatisfy({ $0.isFinite }) else {
        throw VisibilityGridContractError.invalidArgument(
            "\(name) must contain 16 finite values"
        )
    }
    return result
}

private func requiredInt64List(
    _ values: [String: Any],
    _ name: String
) throws -> [Int64] {
    guard let typed = values[name] as? FlutterStandardTypedData else {
        throw VisibilityGridContractError.invalidArgument(
            "\(name) must be Int64List"
        )
    }
    return typed.data.withUnsafeBytes {
        Array($0.bindMemory(to: Int64.self))
    }
}

private func requiredInt32List(
    _ values: [String: Any],
    _ name: String
) throws -> [Int32] {
    guard let typed = values[name] as? FlutterStandardTypedData else {
        throw VisibilityGridContractError.invalidArgument(
            "\(name) must be Int32List"
        )
    }
    return typed.data.withUnsafeBytes {
        Array($0.bindMemory(to: Int32.self))
    }
}

private func nowNanoseconds() -> Int64 {
    Int64(DispatchTime.now().uptimeNanoseconds)
}

private var syntheticInputAllowed: Bool {
#if DEBUG
    true
#else
    false
#endif
}
