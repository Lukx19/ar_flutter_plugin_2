package com.uhg0.ar_flutter_plugin_2.storeversion

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeStoreFormatVersionsTest {
    @Test
    fun reportsTheVersionsTheStoresWrite() {
        val report = NativeStoreFormatVersions.report()
        assertEquals(7, report["nativePhotoStore"])
        assertEquals(9, report["nativePhotoRootSchema"])
        assertEquals(listOf(5, 6, 8, 9), report["nativePhotoRootSchemasReadable"])
        assertEquals(3, report["nativePreviewCache"])
        assertEquals(setOf("5", "6", "8", "9"), NativeStoreFormatVersions.PHOTO_ROOT_SCHEMAS_READABLE)
    }
}
