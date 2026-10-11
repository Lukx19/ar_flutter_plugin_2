package com.uhg0.ar_flutter_plugin_2.gridrenderer

import android.opengl.EGL14
import android.opengl.GLES30
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GlFailureCheckTest {
    @Test
    fun `codes match the platform constants`() {
        assertEquals(EGL14.EGL_CONTEXT_LOST, GlFailureCheck.EGL_CONTEXT_LOST)
        assertEquals(GLES30.GL_OUT_OF_MEMORY, GlFailureCheck.GL_OUT_OF_MEMORY)
    }

    @Test
    fun `a lost context fails the render thread and a destroyed surface does not`() {
        assertNotNull(GlFailureCheck.swapFailure(EGL14.EGL_CONTEXT_LOST))
        assertNull(GlFailureCheck.swapFailure(EGL14.EGL_BAD_SURFACE))
        assertNull(GlFailureCheck.swapFailure(EGL14.EGL_SUCCESS))
    }

    @Test
    fun `an out of memory buffer allocation fails the render thread`() {
        assertNotNull(GlFailureCheck.allocationFailure(GLES30.GL_OUT_OF_MEMORY, 1 shl 20))
        assertNull(GlFailureCheck.allocationFailure(GLES30.GL_NO_ERROR, 1 shl 20))
    }
}
