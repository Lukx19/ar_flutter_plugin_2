package com.uhg0.ar_flutter_plugin_2.visibilitygrid

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class SyntheticSphereDepthFixtureTest {
    private val fixture = SyntheticSphereDepthFixture(identityVisibilityGridTransform().toList())

    private fun hits(view: SyntheticSphereDepthFixture.View): List<DoubleArray> =
        List(SyntheticSphereDepthFixture.SAMPLES) { index ->
            val depth = view.depths[index] / 1000.0
            val x = (SyntheticSphereDepthFixture.pixelX(index) - 640) / 1000.0 * depth
            val y = (480 - SyntheticSphereDepthFixture.pixelY(index)) / 1000.0 * depth
            val m = view.groupPose
            DoubleArray(3) { axis -> m[axis] * x + m[4 + axis] * y - m[8 + axis] * depth + m[12 + axis] }
        }

    @Test fun `each dense image backprojects onto the shell around its interior camera`() {
        for (view in fixture.views) {
            val p = view.groupPose
            assertTrue(sqrt(p[12]*p[12] + p[13]*p[13] + p[14]*p[14]) <= .5)
            assertEquals(4096, view.depths.size)
            assertTrue(view.depths.all { it > 0 })
            hits(view).forEach { h ->
                assertTrue(abs(sqrt(h.sumOf { it * it }) - 2.0) < .002)
            }
        }
    }

    @Test fun `successive visible patches overlap while gradually covering all six directions`() {
        val seen = HashSet<List<Int>>()
        var previous = emptySet<List<Int>>()
        var first = 0
        val extrema = DoubleArray(6)
        for ((index, view) in fixture.views.withIndex()) {
            val patch = hits(view)
            val cells = patch.map { h -> h.map { floor(it / .1).toInt() } }.toSet()
            if (index > 0) assertTrue(cells.intersect(previous).isNotEmpty())
            val before = seen.size
            seen.addAll(cells)
            assertTrue("view $index introduces new shell cells", seen.size > before)
            if (index == 0) first = seen.size
            for (h in patch) for (axis in 0..2) {
                extrema[axis*2] = maxOf(extrema[axis*2], h[axis])
                extrema[axis*2+1] = maxOf(extrema[axis*2+1], -h[axis])
            }
            previous = cells
        }
        assertTrue(first * 4 < seen.size)
        assertTrue(extrema.all { it > 1.95 })
    }

    @Test fun `revisiting a view borrows identical buffers and depth evidence`() {
        val original = fixture.views[0]
        repeat(3) {
            assertSame(original, fixture.views[0])
            assertSame(original.depths, fixture.views[0].depths)
            assertSame(original.worldPose, fixture.views[0].worldPose)
        }
    }

    @Test fun `photo quaternion and depth transform describe the same head orientation`() {
        for (view in fixture.views) {
            val q=view.rotationQuaternion
            val x=q[0].toDouble(); val y=q[1].toDouble(); val z=q[2].toDouble(); val w=q[3].toDouble()
            assertEquals(1.0, x*x+y*y+z*z+w*w, 1e-6)
            val forward=doubleArrayOf(-2*(x*z+w*y), -2*(y*z-w*x), -(1-2*x*x-2*y*y))
            for (axis in 0..2) assertEquals(-view.worldPose[8+axis],forward[axis],1e-6)
        }
    }

    @Test fun `group translation moves the shell center without changing camera relative depth`() {
        val transform = identityVisibilityGridTransform().also { it[12]=7.0; it[13]=-2.0; it[14]=3.0 }
        val shifted = SyntheticSphereDepthFixture(transform.toList())
        for (i in fixture.views.indices) {
            assertArrayEquals(fixture.views[i].depths, shifted.views[i].depths)
            for (axis in 0..2) assertEquals(fixture.views[i].worldPose[12+axis] + transform[12+axis], shifted.views[i].worldPose[12+axis], 1e-9)
        }
    }
}
