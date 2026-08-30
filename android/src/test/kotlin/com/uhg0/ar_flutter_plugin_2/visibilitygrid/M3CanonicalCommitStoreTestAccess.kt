package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import java.lang.reflect.InvocationTargetException

/** Test-source-only access to the private stage cuts retained for focused fault evidence. */
internal fun M3CanonicalCommitStore.stage(
    intent: M3PreparedIntent,
    base: M3CanonicalStateView,
    fault: M3CanonicalCowFault? = null,
): M3CanonicalCowStageResult = invokePrivate(
    "stage", arrayOf(M3PreparedIntent::class.java, M3CanonicalStateView::class.java, M3CanonicalCowFault::class.java),
    arrayOf(intent, base, fault),
) as M3CanonicalCowStageResult

/** Test-source-only access to private selector publication cuts; production has only commit(). */
internal fun M3CanonicalCommitStore.publish(
    generation: M3CanonicalCowGeneration,
    generationZero: M3CanonicalStateView,
    fault: M3CanonicalSelectorFault? = null,
): M3CanonicalPublishResult = invokePrivate(
    "publish", arrayOf(M3CanonicalCowGeneration::class.java, M3CanonicalStateView::class.java, M3CanonicalSelectorFault::class.java),
    arrayOf(generation, generationZero, fault),
) as M3CanonicalPublishResult

private fun M3CanonicalCommitStore.invokePrivate(name: String, types: Array<Class<*>>, values: Array<out Any?>): Any? = try {
    javaClass.getDeclaredMethod(name, *types).also { it.isAccessible = true }.invoke(this, *values)
} catch (wrapped: InvocationTargetException) {
    throw requireNotNull(wrapped.targetException)
}
