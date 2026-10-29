package com.openpro.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class SavedCameraTest {
    @Test
    fun defaultNamesTellCamerasOfTheSameModelApart() {
        val a = SavedCamera("aa", "GoPro 1234", serial = "C3501234561234", modelId = 66)
        val b = SavedCamera("bb", "GoPro 5678", serial = "C3501234565678", modelId = 66)
        assertEquals("HERO 1234", a.name)
        assertEquals("HERO 5678", b.name)
        assertEquals("HERO 1234", a.folderName)
    }

    @Test
    fun fallsBackToTheSerialNumber() {
        assertEquals("HERO 9876", SavedCamera("aa", "GoPro", serial = "C350000009876", modelId = 66).name)
        assertEquals("GoPro 1234", SavedCamera("aa", "GoPro 1234").name)
    }

    @Test
    fun aChosenNameWinsAndTheFolderCanStay() {
        val cam = SavedCamera("aa", "GoPro 1234", modelId = 66, customName = "Helmet cam", customFolder = "HERO 1234")
        assertEquals("Helmet cam", cam.name)
        assertEquals("HERO 1234", cam.folderName)
    }
}
