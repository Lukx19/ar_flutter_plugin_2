package com.uhg0.ar_flutter_plugin_2.m0

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import java.io.File
import java.io.FileOutputStream

/** Test-only child process that dies at the durable root cut. */
class M0CrashBeforeRootSwitchService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val path = intent?.getStringExtra(EXTRA_DIRECTORY) ?: return START_NOT_STICKY
        val directory = File(path)
        require(directory.mkdirs() || directory.isDirectory)
        FileOutputStream(File(directory, STARTED_FILE)).use { output ->
            output.write(Process.myPid().toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        M0Schema5DurableRegionCutStore(
            directory = directory,
            initialCuts = listOf(cut(1, 0), cut(1, 1)),
            directorySync = M0DirectorySync.strictAndroid,
            onBeforeProcessDeathCut = { Process.killProcess(Process.myPid()) },
        ).publish(
            replacement = listOf(cut(2, 0), cut(2, 1)),
            fault = M0DurableCutFaultPoint.processDeathBeforeRootSwitch,
        )
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun cut(generation: Long, coordinate: Int) = M0RegionPairCut(
        region = M0RegionCoordinate(coordinate, 0, 0),
        generation = generation,
        geometryRevision = generation,
        coverageRevision = generation,
        captureEvaluatedThrough = 4,
        pendingThrough = 2,
    )

    companion object {
        const val EXTRA_DIRECTORY = "directory"
        const val STARTED_FILE = "crash_service_started"
    }
}
