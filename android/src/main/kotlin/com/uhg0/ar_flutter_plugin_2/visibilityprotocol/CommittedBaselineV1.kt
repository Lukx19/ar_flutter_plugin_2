package com.uhg0.ar_flutter_plugin_2.visibilityprotocol

internal const val A_IDENTITY_MATRIX_IDENTITY =
    "3ff0000000000000,0,0,0,0,3ff0000000000000,0,0,0,0,3ff0000000000000,0,0,0,0,3ff0000000000000"

data class CommittedBaselineV1(
    val transactionId: Long,
    val geometryRevision: Long,
    val lineageRevision: Long,
    val styleRevision: Long,
    val evidenceRevision: Long = 0,
    val captureRevision: Long = 0,
    val coverageRevision: Long = 0,
    val producedStyleRevision: Long = 0,
    val regionManifestRevision: Long = 0,
    val schemaRootRevision: Long = 0,
    val nextSurfaceIdHighWater: Long = 0,
    val schemaRootHashIdentity: String = "",
    val manifestRootHashIdentity: String = "",
    val groupFrameConvention: Int = 1,
    val matrixConvention: Int = 1,
    val directionConvention: Int = 1,
    val normalEncoding: Int = 1,
    val groupFromWorldIdentity: String = A_IDENTITY_MATRIX_IDENTITY,
    val worldFromGroupIdentity: String = A_IDENTITY_MATRIX_IDENTITY,
) {
    init {
        require(
            listOf(
                transactionId,
                geometryRevision,
                lineageRevision,
                styleRevision,
                evidenceRevision,
                captureRevision,
                coverageRevision,
                producedStyleRevision,
                regionManifestRevision,
                schemaRootRevision,
                nextSurfaceIdHighWater,
            ).all { it >= 0 },
        )
    }

    fun resultRevisionCut(): LongArray = longArrayOf(
        evidenceRevision,
        geometryRevision,
        lineageRevision,
        captureRevision,
        coverageRevision,
        producedStyleRevision,
        styleRevision,
        regionManifestRevision,
        schemaRootRevision,
        nextSurfaceIdHighWater,
        1L,
        0L,
    )

    companion object {
        val ZERO = CommittedBaselineV1(0, 0, 0, 0)

        fun forFreshBinding(value: CommittedBaselineV1): CommittedBaselineV1 =
            value.copy(transactionId = 0)

        fun fromRestoredConfiguration(
            configuration: StartRequestCodecV2.Configuration,
        ): CommittedBaselineV1 {
            val revisions = configuration.restoredRevisions
            require(revisions.size >= 7) { "A canonical START cut has seven revisions" }
            return CommittedBaselineV1(
                transactionId = 0,
                geometryRevision = revisions[1],
                lineageRevision = revisions[2],
                styleRevision = revisions[6],
                evidenceRevision = revisions[0],
                captureRevision = revisions[3],
                coverageRevision = revisions[4],
                producedStyleRevision = revisions[5],
                regionManifestRevision = revisions[7],
                schemaRootRevision = revisions[8],
                nextSurfaceIdHighWater = revisions[9],
                schemaRootHashIdentity = configuration.schemaRootHashIdentity,
                manifestRootHashIdentity = configuration.manifestRootHashIdentity,
                groupFrameConvention = configuration.groupFrameConvention,
                matrixConvention = configuration.matrixConvention,
                directionConvention = configuration.directionConvention,
                normalEncoding = configuration.normalEncoding,
                groupFromWorldIdentity = configuration.groupFromWorldIdentity,
                worldFromGroupIdentity = configuration.worldFromGroupIdentity,
            )
        }
    }
}
