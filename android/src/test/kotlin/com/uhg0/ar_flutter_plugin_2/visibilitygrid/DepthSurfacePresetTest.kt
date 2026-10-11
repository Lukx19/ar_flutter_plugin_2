package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DepthSurfacePresetTest {
    private fun payload(preset: Int): ByteArray = ByteArray(49).also {
        it[0] = 1; it[16] = 2
        it[6] = 0x40; it[8] = 0x80.toByte(); it[22] = 0x40; it[24] = 0x80.toByte()
        ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(32, 1).putLong(40, 2)
        it[48] = preset.toByte()
    }

    @Test fun `current preset preserves calibration and every experiment has bounded dispatch`() {
        assertEquals(DepthEvidenceConfiguration(), DepthSurfacePreset.VIEW_RAY_WEIGHTED.configuration())
        assertEquals(DepthSurfaceEstimationMode.LOCAL_NORMALS, DepthSurfacePreset.LOCAL_NORMALS.configuration().surfaceEstimationMode)
        assertEquals(DepthPlaneFitStrategy.REUSABLE_SAMPLE_SUPPORT, DepthSurfacePreset.REUSABLE_COMPATIBLE_PLANES.configuration().planeFitStrategy)
        assertEquals(DepthPlaneFitStrategy.BULK_CELL_MOMENTS, DepthSurfacePreset.BULK_COMPATIBLE_PLANES.configuration().planeFitStrategy)
        assertEquals(5, DepthSurfacePreset.entries.map { it.wireName }.toSet().size)
        for (preset in DepthSurfacePreset.entries) {
            DepthEvidenceKernel(preset.configuration()).use { kernel ->
                val receipt = kernel.resourceReceipt()
                assertTrue(receipt.modeledMaximumSemanticStateBytes <= receipt.semanticStateBudgetBytes)
            }
        }
    }

    @Test fun `unsupported malformed and nonpositive generation selections are refused`() {
        for (bytes in listOf(payload(5), payload(255), payload(0).copyOf(48), payload(0).also { it.fill(0,32,40) })) {
            assertThrows(IllegalArgumentException::class.java) { DepthSurfaceSelection.decode(bytes) }
        }
    }

    @Test fun `active group accepts identical selection and refuses a different estimator or identity`() {
        val active = DepthSurfaceSelection.decode(payload(2))
        active.validateReplacement(active, false)
        val changedPreset = DepthSurfaceSelection.decode(payload(4))
        assertThrows(IllegalStateException::class.java) { changedPreset.validateReplacement(active, false) }
        val changedGroup = DepthSurfaceSelection.decode(payload(2).also { it[16] = 3 })
        assertThrows(IllegalStateException::class.java) { changedGroup.validateReplacement(active, false) }
        changedPreset.validateReplacement(active, true)
    }
}
