package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.io.File

/** Explicit process-local exclusivity for tests that exercise candidate admission. */
internal abstract class ExclusiveFakeStorageBudget : CanonicalStorageBudget {
    private data class ExclusiveToken(val delegate: Any, val target: String)
    private val targets = mutableSetOf<String>()

    protected abstract fun reserveBytes(bytes: Long): Any?
    protected abstract fun commitBytes(token: Any, actualBytes: Long)
    protected abstract fun releaseBytes(token: Any)

    final override fun reserve(bytes: Long): Any? = reserveBytes(bytes)

    final override fun reserveCandidateExclusive(
        staging: File,
        target: File,
        fileBytes: Map<String, Long>,
        maximumPhysicalBytes: Long,
    ): CanonicalCandidateReservation = synchronized(targets) {
        val identity = target.canonicalPath
        if (!targets.add(identity)) return@synchronized CanonicalCandidateReservation.TargetReserved
        val delegate = try { reserveCandidate(staging, target, fileBytes, maximumPhysicalBytes) } catch (failure: Exception) {
            targets.remove(identity); throw failure
        }
        if (delegate == null) {
            targets.remove(identity)
            CanonicalCandidateReservation.QuotaRefused
        } else CanonicalCandidateReservation.Reserved(ExclusiveToken(delegate, identity))
    }

    final override fun commit(token: Any, actualBytes: Long) = commitBytes(unwrap(token), actualBytes)
    final override fun release(token: Any) = releaseBytes(unwrap(token))

    private fun unwrap(token: Any): Any = if (token is ExclusiveToken) {
        synchronized(targets) { require(targets.remove(token.target)) }
        token.delegate
    } else token
}
