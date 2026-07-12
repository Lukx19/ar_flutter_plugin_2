package com.uhg0.ar_flutter_plugin_2.capture

import java.util.UUID

internal object CaptureImageIdGenerator {
    fun next(): String = "img_${UUID.randomUUID()}"
}
