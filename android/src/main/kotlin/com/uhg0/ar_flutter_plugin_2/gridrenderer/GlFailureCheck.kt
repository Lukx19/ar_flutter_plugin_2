package com.uhg0.ar_flutter_plugin_2.gridrenderer

/**
 * GL and EGL failures the render thread cannot recover from in place. The
 * render thread throws on them, so its teardown requires a reset and the
 * host reports `RENDERER_FAILED` instead of drawing nothing while replying 0.
 */
internal object GlFailureCheck {
    /** `EGL14.EGL_CONTEXT_LOST`: power management or the driver dropped the context. */
    const val EGL_CONTEXT_LOST = 0x300E

    /** `GLES30.GL_OUT_OF_MEMORY`: a buffer allocation failed; its content is undefined. */
    const val GL_OUT_OF_MEMORY = 0x0505

    /**
     * Failure of a failed `eglSwapBuffers` with [eglError], or null when the
     * context survives (a destroyed surface during shutdown is no failure).
     */
    fun swapFailure(eglError: Int): String? =
        if (eglError == EGL_CONTEXT_LOST) "EGL context lost (0x${Integer.toHexString(eglError)})" else null

    /** Failure of a buffer allocation followed by [glError], or null. */
    fun allocationFailure(glError: Int, bytes: Int): String? =
        if (glError == GL_OUT_OF_MEMORY) "GL out of memory allocating $bytes bytes" else null
}
