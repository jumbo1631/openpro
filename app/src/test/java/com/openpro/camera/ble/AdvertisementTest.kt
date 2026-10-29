package com.openpro.camera.ble

import com.openpro.camera.CameraModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvertisementTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun readsTheModelFromBothSchemas() {
        // [schema][status][model][capabilities]…
        assertEquals(66, GoProBle.advertisedModelId(bytes(2, 0x01, 66, 0x3F)))
        assertEquals(62, GoProBle.advertisedModelId(bytes(3, 0x00, 62, 0xFF, 0, 0, 0, 0, 0, 0x41, 0x42)))
    }

    @Test
    fun ignoresWhatItCantRead() {
        assertNull(GoProBle.advertisedModelId(null))
        assertNull(GoProBle.advertisedModelId(bytes(2, 0)))
        assertNull(GoProBle.advertisedModelId(bytes(1, 0, 50)))
    }

    @Test
    fun knowsWhichModelsWork() {
        assertTrue(CameraModels.isUsable(66))
        assertTrue(CameraModels.isUsable(62))
        assertFalse(CameraModels.isUsable(50)) // HERO8 Black
        assertFalse(CameraModels.isUsable(250)) // unknown
        assertEquals(
            "HERO8 Black isn't supported by OpenPro. Camera models on the home screen lists the cameras it works with.",
            CameraModels.unsupportedMessage(50),
        )
    }
}
