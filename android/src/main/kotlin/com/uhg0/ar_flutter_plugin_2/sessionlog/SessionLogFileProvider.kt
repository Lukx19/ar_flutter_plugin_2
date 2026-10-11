package com.uhg0.ar_flutter_plugin_2.sessionlog

import androidx.core.content.FileProvider

/**
 * The session log share provider. Its own class, so the manifest merger never
 * merges it with an app's or another library's `androidx.core` FileProvider
 * entry (one provider class may be declared once).
 */
class SessionLogFileProvider : FileProvider()
