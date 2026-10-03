package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal const val SYNTHETIC_SPHERE_VIEW_COUNT = 20
internal const val SYNTHETIC_DENSE_CAMPAIGN_VARIANTS = 25
internal const val SYNTHETIC_CAMPAIGN_FEATURE_SAMPLES = 5
internal const val SYNTHETIC_CAMPAIGN_FEATURE_FIXTURE = "visibleBackgroundCatalog"

internal fun syntheticDenseCampaignCameraX(variant: Int): Double {
    require(variant in 0 until SYNTHETIC_DENSE_CAMPAIGN_VARIANTS)
    return if (variant < 5) -0.48 + variant * 0.02 else -0.38 + (variant - 5) * 0.04
}

/** Deterministic copied-source emitter shared by JVM and debug emulator gates. */
internal class SyntheticVisibilityObservationSource(
    private val runtime: AndroidVisibilityGridRuntime,
    private val ownership: () -> VisibilityObservationOwnership?,
) : AutoCloseable {
    private var sequence = 0L
    private var worldFromCameraGl = identityVisibilityGridTransform()
    private var groupFromCameraGl = identityVisibilityGridTransform()
    private var sweepAnchorWorldFromCameraGl = identityVisibilityGridTransform()
    private var sweepGroupFromWorldGl = identityVisibilityGridTransform().toList()
    private var cachedDepthGrids: List<List<VisibilityDepthSample>>? = null
    private var cachedCampaignDepthGrids: List<List<VisibilityDepthSample>>? = null
    private var campaignFeatureCoordinates: DoubleArray? = null
    private var campaignFeatureFirstColumns: IntArray? = null
    private var campaignCameraPoses: Array<DoubleArray>? = null
    private var lastCopiedFeatureTimestampNs: Long? = null
    private var lastCopiedDepthTimestampNs: Long? = null
    private val featureLeasePool = FeatureSamplesLeasePool()
    private val depthLeasePool = DepthSamplesLeasePool()

    /**
     * Fences the native latest-only lanes before an end-of-workload receipt.
     *
     * The producer scheduler can stop while a copied observation is still
     * resident in the runtime. This is intentionally explicit instead of
     * being hidden in [packedLeaseReceipts], because ordinary snapshots are
     * also used while mapping stalls are being observed.
     */
    internal fun awaitSyntheticIdle() {
        if (runtime.isSyntheticSource()) runtime.awaitDebugFixtureIdle()
    }

    internal fun packedLeaseReceipts(): Map<String, SampleLeasePoolReceipt> = mapOf(
        "feature" to featureLeasePool.receipt(),
        "depth" to depthLeasePool.receipt(),
    )

    /** Refuses new copies; active mapper borrows release their storage after drain. */
    override fun close() {
        featureLeasePool.close()
        depthLeasePool.close()
        cachedDepthGrids = null
        cachedCampaignDepthGrids = null
        campaignFeatureCoordinates = null
        campaignFeatureFirstColumns = null
        campaignCameraPoses = null
    }

    /** Prebuilds five legacy views or 25 finite campaign views of the fixed plane and patch. */
    fun prepareDenseDepthGrids(campaignVariants: Boolean = false) {
        val cut = requireNotNull(ownership())
        anchor(cut.groupFrame.worldFromGroupGl.toDoubleArray(), cut.groupFrame)
        if (campaignVariants && campaignFeatureCoordinates == null) prepareCampaignFeatures()
        if (if (campaignVariants) cachedCampaignDepthGrids != null else cachedDepthGrids != null) return
        val grids = List(if (campaignVariants) SYNTHETIC_DENSE_CAMPAIGN_VARIANTS else 5) { marker ->
            val cameraX = if (campaignVariants) syntheticDenseCampaignCameraX(marker) else (marker - 2) * 0.04
            VisibilityDepthObservation.copySamples(List(V2_DEPTH_SAMPLE_CAPACITY) { index ->
                val x = 10 + index % 64 * 20
                val y = 7 + index / 64 * 15
                val patchX = (x - SYNTHETIC_PRINCIPAL_X) / SYNTHETIC_FOCAL_LENGTH * 0.75 + cameraX
                val patchY = (y - SYNTHETIC_PRINCIPAL_Y) / SYNTHETIC_FOCAL_LENGTH * 0.75
                VisibilityDepthSample(
                    x = x,
                    y = y,
                    depthMillimeters = if (kotlin.math.abs(patchX) < 0.10 &&
                        kotlin.math.abs(patchY) < 0.10) 750 else 1_000,
                    confidence = 255,
                )
            })
        }
        if (campaignVariants) cachedCampaignDepthGrids = grids else cachedDepthGrids = grids
    }

    fun emitDenseDepthGrid(timestampNs: Long, marker: Int, campaignVariants: Boolean = false): Boolean {
        require(marker in 0 until if (campaignVariants) SYNTHETIC_DENSE_CAMPAIGN_VARIANTS else 5)
        val cut = ownership() ?: return false
        val samples = requireNotNull(if (campaignVariants) cachedCampaignDepthGrids else cachedDepthGrids)[marker]
        val pose = if (campaignVariants) requireNotNull(campaignCameraPoses)[marker] else worldFromCameraGl.copyOf()
        val cameraX = if (campaignVariants) syntheticDenseCampaignCameraX(marker) else (marker - 2) * 0.04
        if (!campaignVariants) for (axis in 0..2) pose[12 + axis] += pose[axis] * cameraX
        val lease = depthLeasePool.tryAcquire(cut) ?: return false
        lease.clear()
        try {
            samples.forEach { sample ->
                check(lease.append(sample.x, sample.y, sample.depthMillimeters, sample.confidence))
            }
            val accepted = runtime.offerDepth(VisibilityDepthObservation.fromPacked(
                ownership = cut,
                frame = syntheticFrame(VisibilityObservationSource.SYNTHETIC_DEPTH, timestampNs, pose),
                packedSamples = lease.samples,
                sourceRejectedSamples = 0,
            ))
            if (accepted) lastCopiedDepthTimestampNs = timestampNs
            return accepted
        } catch (error: RuntimeException) {
            lease.close()
            throw error
        }
    }

    /**
     * A stable world catalog on the background wall. At y=.15 the ray crosses
     * the foreground depth at y=.1125, outside its .10-metre half extent.
     * Every frame contains the complete visible five-point selection, so
     * latest-frame coalescing cannot bias a rotating one-point normal sample.
     */
    private fun prepareCampaignFeatures() {
        val coordinates = DoubleArray(48 * 3)
        repeat(48) { column ->
            val local = localToGroup((-520 + column * 20) / 1_000.0, 0.15, -1.0)
            local.copyInto(coordinates, column * 3)
        }
        val firstColumns = IntArray(SYNTHETIC_DENSE_CAMPAIGN_VARIANTS)
        val poses = Array(SYNTHETIC_DENSE_CAMPAIGN_VARIANTS) { variant ->
            val cameraX = syntheticDenseCampaignCameraX(variant)
            val cameraMillimetres = (cameraX * 1_000).roundToInt()
            firstColumns[variant] = (cameraMillimetres - 40 + 520) / 20
            worldFromCameraGl.copyOf().also { pose ->
                for (axis in 0..2) pose[12 + axis] += pose[axis] * cameraX
            }
        }
        campaignFeatureCoordinates = coordinates
        campaignFeatureFirstColumns = firstColumns
        campaignCameraPoses = poses
    }

    fun emitCampaignFeatureFrame(timestampNs: Long, variant: Int): Boolean {
        require(variant in 0 until SYNTHETIC_DENSE_CAMPAIGN_VARIANTS)
        val cut = ownership() ?: return false
        val coordinates = requireNotNull(campaignFeatureCoordinates)
        val first = requireNotNull(campaignFeatureFirstColumns)[variant]
        val pose = requireNotNull(campaignCameraPoses)[variant]
        val lease = featureLeasePool.tryAcquire(cut) ?: return false
        lease.clear()
        try {
            repeat(SYNTHETIC_CAMPAIGN_FEATURE_SAMPLES) { index ->
                val column = first + index
                val id = 300_000 + column
                check(lease.acceptId(id))
                check(lease.append(id, coordinates[column * 3], coordinates[column * 3 + 1],
                    coordinates[column * 3 + 2], 1.0))
            }
            val accepted = runtime.offerFeature(VisibilityFeatureObservation.fromPacked(
                ownership = cut,
                frame = syntheticFrame(VisibilityObservationSource.SYNTHETIC_FEATURE, timestampNs, pose),
                packedSamples = lease.samples,
                sourceRejectedSamples = 0,
            ))
            if (accepted) lastCopiedFeatureTimestampNs = timestampNs
            return accepted
        } catch (error: RuntimeException) {
            lease.close()
            throw error
        }
    }

    /** Anchors feature geometry to the active group and depth to one exact AR world pose. */
    fun anchor(worldFromCameraGl: DoubleArray, groupFrame: VisibilityGroupFrame) {
        VisibilityCameraPose.copyOf(worldFromCameraGl)
        this.worldFromCameraGl = worldFromCameraGl.copyOf()
        groupFromCameraGl = compose(groupFrame.groupFromWorldGl, worldFromCameraGl)
        sweepAnchorWorldFromCameraGl = worldFromCameraGl.copyOf()
        sweepGroupFromWorldGl = groupFrame.groupFromWorldGl
        VisibilityCameraPose.copyOf(groupFromCameraGl)
    }

    fun setDepthCapability(capability: VisibilityDepthCapability) {
        runtime.configureSyntheticSource(capability)
    }

    fun emitFeature(
        timestampNs: Long,
        marker: Int = 0,
        lateralMarker: Int = marker,
        callbackCopyNs: Long = 0,
    ): Boolean {
        require(callbackCopyNs >= 0)
        val cut = ownership() ?: return false
        val frame = syntheticFrame(
            source = VisibilityObservationSource.SYNTHETIC_FEATURE,
            timestampNs = timestampNs,
        )
        val local = localToGroup(lateralMarker.toDouble() / 100.0, 0.0, -1.0)
        val lease = featureLeasePool.tryAcquire(cut) ?: return false
        lease.clear()
        val id = marker.coerceAtLeast(0)
        try {
            check(lease.acceptId(id))
            check(lease.append(id, local[0], local[1], local[2], 1.0))
            val accepted = runtime.offerFeature(
                VisibilityFeatureObservation.fromPacked(
                    ownership = cut,
                    frame = frame,
                    packedSamples = lease.samples,
                    sourceRejectedSamples = 0,
                ),
                callbackCopyNs,
            )
            if (accepted) lastCopiedFeatureTimestampNs = timestampNs
            return accepted
        } catch (error: RuntimeException) {
            lease.close()
            throw error
        }
    }

    fun emitDepth(
        timestampNs: Long,
        marker: Int = 0,
        lateralMarker: Int = marker,
    ): Boolean {
        val cut = ownership() ?: return false
        val frame = syntheticFrame(
            source = VisibilityObservationSource.SYNTHETIC_DEPTH,
            timestampNs = timestampNs,
        )
        val lease = depthLeasePool.tryAcquire(cut) ?: return false
        lease.clear()
        try {
            check(lease.append(
                SYNTHETIC_PRINCIPAL_X + lateralMarker.coerceAtLeast(0) * 20,
                SYNTHETIC_PRINCIPAL_Y,
                SYNTHETIC_DEPTH_MILLIMETERS,
                255,
            ))
            val accepted = runtime.offerDepth(VisibilityDepthObservation.fromPacked(
                ownership = cut,
                frame = frame,
                packedSamples = lease.samples,
                sourceRejectedSamples = 0,
            ))
            if (accepted) lastCopiedDepthTimestampNs = timestampNs
            return accepted
        } catch (error: RuntimeException) {
            lease.close()
            throw error
        }
    }

    /** Exercises both per-observation sample ceilings without expanding the scene. */
    fun emitMaximumSamples(featureTimestampNs: Long, depthTimestampNs: Long): Pair<Boolean, Boolean> =
        emitMaximumFeature(featureTimestampNs) to emitMaximumDepth(depthTimestampNs)

    /** Drives the full feature source through a host or emulator integration owner. */
    fun emitMaximumFeature(featureTimestampNs: Long): Boolean {
        val cut = ownership() ?: return false
        val copiedTimestampNs = nextFeatureTimestamp(featureTimestampNs)
        val lease = featureLeasePool.tryAcquire(cut) ?: return false
        try {
            lease.clear()
            for (index in 0 until V2_FEATURE_SAMPLE_CAPACITY) {
                val local = localToGroup(
                    0.30 + (index % 40).toDouble() / 4_000.0,
                    (index / 40).toDouble() / 4_000.0,
                    -1.0,
                )
                val id = 100_000 + index
                check(lease.acceptId(id))
                check(lease.append(id, local[0], local[1], local[2], 1.0))
            }
            val accepted = runtime.offerFeature(
                VisibilityFeatureObservation.fromPacked(
                    ownership = cut,
                    frame = syntheticFrame(VisibilityObservationSource.SYNTHETIC_FEATURE, copiedTimestampNs),
                    packedSamples = lease.samples,
                    sourceRejectedSamples = 0,
                ),
            )
            if (accepted) lastCopiedFeatureTimestampNs = copiedTimestampNs
            return accepted
        } catch (error: RuntimeException) {
            lease.close()
            throw error
        }
    }

    /** A fresh maximum-depth offer after a prior exact canonical ACK. */
    fun emitMaximumDepth(timestampNs: Long): Boolean {
        val cut = ownership() ?: return false
        val copiedTimestampNs = nextDepthTimestamp(timestampNs)
        val lease = depthLeasePool.tryAcquire(cut) ?: return false
        try {
            lease.clear()
            for (index in 0 until V2_DEPTH_SAMPLE_CAPACITY) {
                check(lease.append(
                    SYNTHETIC_PRINCIPAL_X - 24 + index % 48,
                    SYNTHETIC_PRINCIPAL_Y - 16 + index / 48,
                    // This near-field batch probes the full selected-sample
                    // ceiling through bounded canonical work.
                    MAXIMUM_SAMPLE_DEPTH_MILLIMETERS,
                    255,
                ))
            }
            val accepted = runtime.offerDepth(
                VisibilityDepthObservation.fromPacked(
                    ownership = cut,
                    frame = syntheticFrame(VisibilityObservationSource.SYNTHETIC_DEPTH, copiedTimestampNs),
                    packedSamples = lease.samples,
                    sourceRejectedSamples = 0,
                ),
            )
            if (accepted) lastCopiedDepthTimestampNs = copiedTimestampNs
            return accepted
        } catch (error: RuntimeException) {
            lease.close()
            throw error
        }
    }

    /** Emits a retry after a maximum batch without regressing its source timestamp. */
    fun emitMonotonicDepth(
        timestampNs: Long,
        marker: Int = 0,
        lateralMarker: Int = marker,
    ): Boolean = emitDepth(nextDepthTimestamp(timestampNs), marker, lateralMarker)

    /** One bounded camera view of a two-metre spherical room around the anchor. */
    fun emitSphereView(
        viewIndex: Int,
        featureTimestampNs: Long,
        depthTimestampNs: Long,
        includeFeature: Boolean = true,
        includeDepth: Boolean = true,
    ): Pair<Boolean, Boolean> {
        require(viewIndex in 0 until SYNTHETIC_SPHERE_VIEW_COUNT)
        // Six headings per ring overlap at the sampled horizontal FOV.
        // Three 45-degree elevation steps plus both poles cover the sphere.
        val yaw = if (viewIndex < 18) ((viewIndex % 6) + 0.5) * PI / 3.0 else 0.0
        val pitch = when (viewIndex) {
            18 -> -PI / 2.0
            19 -> PI / 2.0
            else -> ((viewIndex / 6) - 1) * PI / 4.0
        }
        val cosYaw = cos(yaw)
        val sinYaw = sin(yaw)
        val cosPitch = cos(pitch)
        val sinPitch = sin(pitch)
        val relative = doubleArrayOf(
            cosYaw, 0.0, sinYaw, 0.0,
            -sinYaw * sinPitch, cosPitch, cosYaw * sinPitch, 0.0,
            -sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch, 0.0,
            0.25 * sin(viewIndex * 1.7),
            0.20 * cos(viewIndex * 1.3),
            0.15 * sin(viewIndex * 0.9),
            1.0,
        )
        val worldPose = compose(sweepAnchorWorldFromCameraGl.toList(), relative)
        val groupPose = compose(sweepGroupFromWorldGl, worldPose)
        val center = sweepAnchorWorldFromCameraGl
        val cut = ownership() ?: return false to false
        val featureLease = if (includeFeature) featureLeasePool.tryAcquire(cut) else null
        val depthLease = if (includeDepth) depthLeasePool.tryAcquire(cut) else null
        if ((includeFeature && featureLease == null) || (includeDepth && depthLease == null)) {
            featureLease?.close()
            depthLease?.close()
            return false to false
        }
        featureLease?.clear()
        depthLease?.clear()
        try {
            for (sample in 0 until 64) {
            val pixelX = 40 + (sample % 8) * 171
            val pixelY = 60 + (sample / 8) * 120
            val cameraRay = doubleArrayOf(
                (pixelX - SYNTHETIC_PRINCIPAL_X) / SPHERE_FOCAL_LENGTH,
                (SYNTHETIC_PRINCIPAL_Y - pixelY) / SPHERE_FOCAL_LENGTH,
                -1.0,
            )
            val rayLength = sqrt(cameraRay.sumOf { it * it })
            val ray = DoubleArray(3) { cameraRay[it] / rayLength }
            val worldRay = DoubleArray(3) { axis ->
                worldPose[axis] * ray[0] + worldPose[4 + axis] * ray[1] + worldPose[8 + axis] * ray[2]
            }
            val offset = DoubleArray(3) { worldPose[12 + it] - center[12 + it] }
            val dot = (0..2).sumOf { offset[it] * worldRay[it] }
            val offsetSquared = offset.sumOf { it * it }
            val distance = -dot + sqrt(dot * dot + SPHERE_RADIUS_METERS * SPHERE_RADIUS_METERS - offsetSquared)
            val world = DoubleArray(3) { worldPose[12 + it] + distance * worldRay[it] }
            val group = DoubleArray(3) { axis ->
                sweepGroupFromWorldGl[axis] * world[0] +
                    sweepGroupFromWorldGl[4 + axis] * world[1] +
                    sweepGroupFromWorldGl[8 + axis] * world[2] +
                    sweepGroupFromWorldGl[12 + axis]
            }
            featureLease?.let { lease ->
                val id = 200_000 + viewIndex * 64 + sample
                check(lease.acceptId(id))
                check(lease.append(id, group[0], group[1], group[2], 1.0))
            }
            if (sample % 8 == 4) {
                depthLease?.let { lease ->
                    check(lease.append(
                        pixelX, pixelY,
                        (distance / rayLength * 1000.0).roundToInt(),
                        255,
                    ))
                }
            }
        }
            val feature = featureLease?.let { lease ->
                runtime.offerFeature(VisibilityFeatureObservation.fromPacked(
                    ownership = cut,
                    frame = syntheticFrame(VisibilityObservationSource.SYNTHETIC_FEATURE, featureTimestampNs, groupPose, SPHERE_FOCAL_LENGTH),
                    packedSamples = lease.samples,
                    sourceRejectedSamples = 0,
                ))
            } ?: false
            if (feature) lastCopiedFeatureTimestampNs = featureTimestampNs
            val depth = depthLease?.let { lease ->
                runtime.offerDepth(VisibilityDepthObservation.fromPacked(
                    ownership = cut,
                    frame = syntheticFrame(VisibilityObservationSource.SYNTHETIC_DEPTH, depthTimestampNs, worldPose, SPHERE_FOCAL_LENGTH),
                    packedSamples = lease.samples,
                    sourceRejectedSamples = 0,
                ))
            } ?: false
            if (depth) lastCopiedDepthTimestampNs = depthTimestampNs
            return feature to depth
        } catch (error: RuntimeException) {
            featureLease?.close()
            depthLease?.close()
            throw error
        }
    }

    private fun syntheticFrame(
        source: VisibilityObservationSource,
        timestampNs: Long,
        pose: DoubleArray? = null,
        focalLength: Double = SYNTHETIC_FOCAL_LENGTH,
    ): VisibilityObservationFrame = VisibilityObservationFrame(
        source = source,
        frameSequence = sequence++,
        frameTimestampNs = timestampNs,
        sourceTimestampNs = timestampNs,
        cameraIdentity = "synthetic-camera",
        tracking = true,
        imageOrientation = "landscape_right_x_right_y_down_v1",
        pose = VisibilityCameraPose.copyOf(
            pose ?: if (source == VisibilityObservationSource.SYNTHETIC_FEATURE) {
                groupFromCameraGl
            } else {
                worldFromCameraGl
            },
        ),
        intrinsics = VisibilityCameraIntrinsics(
            imageWidth = SYNTHETIC_IMAGE_WIDTH,
            imageHeight = SYNTHETIC_IMAGE_HEIGHT,
            fx = focalLength,
            fy = focalLength,
            cx = SYNTHETIC_PRINCIPAL_X.toDouble(),
            cy = SYNTHETIC_PRINCIPAL_Y.toDouble(),
        ),
        depthCapability = runtime.snapshot().depthCapability,
    )

    private fun nextFeatureTimestamp(requestedTimestampNs: Long): Long =
        nextTimestamp(requestedTimestampNs, lastCopiedFeatureTimestampNs)

    private fun nextDepthTimestamp(requestedTimestampNs: Long): Long =
        nextTimestamp(requestedTimestampNs, lastCopiedDepthTimestampNs)

    private fun nextTimestamp(requestedTimestampNs: Long, lastCopiedTimestampNs: Long?): Long {
        val nextAfterLast = lastCopiedTimestampNs?.let {
            if (it == Long.MAX_VALUE) Long.MAX_VALUE else it + 1L
        }
        return if (nextAfterLast == null) requestedTimestampNs else
            maxOf(requestedTimestampNs, nextAfterLast)
    }

    private fun localToGroup(x: Double, y: Double, z: Double): DoubleArray = doubleArrayOf(
        groupFromCameraGl[0] * x + groupFromCameraGl[4] * y + groupFromCameraGl[8] * z + groupFromCameraGl[12],
        groupFromCameraGl[1] * x + groupFromCameraGl[5] * y + groupFromCameraGl[9] * z + groupFromCameraGl[13],
        groupFromCameraGl[2] * x + groupFromCameraGl[6] * y + groupFromCameraGl[10] * z + groupFromCameraGl[14],
    )

    private fun compose(left: List<Double>, right: DoubleArray): DoubleArray {
        require(left.size == 16 && right.size == 16)
        return DoubleArray(16) { offset ->
            val column = offset / 4
            val row = offset % 4
            var value = 0.0
            for (index in 0 until 4) {
                value += left[index * 4 + row] * right[column * 4 + index]
            }
            require(value.isFinite())
            value
        }
    }

    private companion object {
        const val SYNTHETIC_IMAGE_WIDTH = 1280
        const val SYNTHETIC_IMAGE_HEIGHT = 960
        const val SYNTHETIC_FOCAL_LENGTH = 2000.0
        const val SYNTHETIC_PRINCIPAL_X = 640
        const val SYNTHETIC_PRINCIPAL_Y = 480
        const val SYNTHETIC_DEPTH_MILLIMETERS = 1000
        const val MAXIMUM_SAMPLE_DEPTH_MILLIMETERS = 300
        const val SPHERE_RADIUS_METERS = 2.0
        const val SPHERE_FOCAL_LENGTH = 1000.0
    }
}
