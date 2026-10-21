package com.uhg0.ar_flutter_plugin_2.capture

import java.io.File

/** App-private directory of the native capture root (`files/capture-v2-native`). */
internal const val NATIVE_CAPTURE_ROOT_DIRECTORY = "capture-v2-native"

/**
 * The exclusively leased durable capture root: its lease, storage budget and session store.
 * Closing releases the store and the budget before the lease.
 */
internal class NativeCaptureDurableRootV2 private constructor(
    val leaseKey: String,
    val lease: NativeCaptureDurableRootLeaseV2,
    val budget: StorageBudgetCoordinatorV2,
    val store: DurableSessionStoreV2,
) : AutoCloseable {
    override fun close() {
        try {
            store.close()
        } finally {
            try { budget.close() } finally { lease.close() }
        }
    }

    companion object {
        fun leaseKey(root: File): String = root.canonicalFile.path

        /** Throws [NativeCaptureDurableRootPendingV2] while another owner holds [root]. */
        fun open(root: File): NativeCaptureDurableRootV2 {
            val lease = NativeCaptureDurableRootLeaseV2.acquire(root)
            var budget: StorageBudgetCoordinatorV2? = null
            try {
                AndroidDescriptorFilesystemV2().use { unbound -> unbound.bind(root).use { } }
                budget = StorageBudgetCoordinatorV2(
                    File(root, "budget"),
                    // Half of free plus held bytes, measured at each check, at least the coexistence floor.
                    StorageBudgetPolicyV2(StorageBudgetQuotaV2.CaptureShare(CAPTURE_COEXISTENCE_BYTES), 0),
                )
                val store = DurableSessionStoreV2(File(root, "store"), budget)
                return NativeCaptureDurableRootV2(leaseKey(root), lease, budget, store)
            } catch (error: Throwable) {
                runCatching { budget?.close() }
                lease.close()
                throw error
            }
        }
    }
}

/**
 * The store of each root an open capture view owns, so a session deletion reaches it while the
 * view holds the root lease. A deletion runs under the registry lock and the owner unregisters
 * before it closes its store, so the store never closes under a running deletion.
 */
internal object NativeCaptureOpenRootStoresV2 {
    private val lock = Any()
    private val stores = mutableMapOf<String, DurableSessionStoreV2>()

    fun register(leaseKey: String, store: DurableSessionStoreV2) = synchronized(lock) { stores[leaseKey] = store }

    fun unregister(leaseKey: String, store: DurableSessionStoreV2) = synchronized(lock) {
        if (stores[leaseKey] === store) stores.remove(leaseKey)
    }

    /** Runs [body] on the open store of [leaseKey]; null when no open view owns that root. */
    fun <T> withOpenStore(leaseKey: String, body: (DurableSessionStoreV2) -> T): T? = synchronized(lock) {
        stores[leaseKey]?.let(body)
    }
}

/** A briefly opened root: the owner closed after the deletion, and its store. */
internal class NativeCaptureRootStoreHandleV2(val owner: AutoCloseable, val store: DurableSessionStoreV2)

/**
 * Deletes the durable photos of a session the app deleted ([DurableSessionStoreV2.deleteSession]):
 * through the open view's store when a view owns the root, otherwise by opening the root briefly.
 * Returns the bytes reclaimed from the storage budget by this call.
 */
internal object NativeCaptureSessionDeletionV2 {
    const val ATTEMPTS = 50
    const val ROOT_WAIT_MILLIS = 50L

    fun delete(
        sessionId: String,
        open: () -> NativeCaptureRootStoreHandleV2,
        leaseKey: String,
        waitForRoot: () -> Unit = { Thread.sleep(ROOT_WAIT_MILLIS) },
    ): Long {
        require(sessionId.isNotEmpty()) { "sessionId is required" }
        repeat(ATTEMPTS) {
            try {
                val handle = open()
                return handle.owner.use { handle.store.deleteSession(sessionId) }
            } catch (_: NativeCaptureDurableRootPendingV2) {
                NativeCaptureOpenRootStoresV2.withOpenStore(leaseKey) { it.deleteSession(sessionId) }?.let { return it }
                // The owner is between unregistering its store and releasing its lease.
                waitForRoot()
            }
        }
        throw NativeCaptureDurableRootPendingV2()
    }

    /** Production entry of the preview channel's `deleteNativeCaptureSession`. */
    fun deleteFromAppFiles(appFiles: File, sessionId: String): Long {
        val root = File(appFiles, NATIVE_CAPTURE_ROOT_DIRECTORY)
        return delete(
            sessionId,
            { NativeCaptureDurableRootV2.open(root).let { NativeCaptureRootStoreHandleV2(it, it.store) } },
            NativeCaptureDurableRootV2.leaseKey(root),
        )
    }
}
