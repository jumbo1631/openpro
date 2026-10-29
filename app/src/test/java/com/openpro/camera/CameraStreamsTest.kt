package com.openpro.camera

import com.openpro.camera.http.CameraStreams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CameraStreamsTest {
    @Test
    fun oneStreamAtATime() {
        val batch = assertNotNullAnd(CameraStreams.tryAcquire(CameraStreams.Who.Batch, "the batch is downloading A"))
        assertNull(CameraStreams.tryAcquire(CameraStreams.Who.Viewer, "a clip's preview is playing"))
        assertEquals("the batch is downloading A", CameraStreams.holder.value?.doing)

        var freed = 0
        CameraStreams.onFree = { freed++ }
        CameraStreams.release(batch)
        assertEquals(1, freed)
        assertNull(CameraStreams.holder.value)

        val viewer = assertNotNullAnd(CameraStreams.tryAcquire(CameraStreams.Who.Viewer, "a clip's preview is playing"))
        // Letting go twice, or letting go of an old hold, changes nothing.
        CameraStreams.release(batch)
        assertEquals(viewer, CameraStreams.holder.value)
        CameraStreams.release(viewer)
        CameraStreams.release(viewer)
        assertEquals(2, freed)
        CameraStreams.onFree = null
    }

    private fun <T> assertNotNullAnd(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
