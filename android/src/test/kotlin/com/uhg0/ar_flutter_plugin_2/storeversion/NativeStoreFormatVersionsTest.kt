package com.uhg0.ar_flutter_plugin_2.storeversion

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeStoreFormatVersionsTest {
    @Test
    fun reportsTheVersionsTheStoresWrite() {
        val report = NativeStoreFormatVersions.report()
        assertEquals(3, report["nativePhotoStore"])
        assertEquals(6, report["nativePhotoRootSchema"])
        assertEquals(listOf(5, 6), report["nativePhotoRootSchemasReadable"])
        assertEquals(1, report["nativePreviewCache"])
        assertEquals(setOf("5", "6"), NativeStoreFormatVersions.PHOTO_ROOT_SCHEMAS_READABLE)
    }
}
