package com.openpro.camera

import com.openpro.camera.link.RelayInfo
import com.openpro.camera.wifi.CameraRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraRouteTest {
    private val relay = CameraRoute.relay(RelayInfo(41234, "abc123"))

    @Test
    fun rewritesCameraUrlsThroughTheRelay() {
        assertEquals(
            "http://127.0.0.1:41234/abc123/videos/DCIM/100GOPRO/GX010001.MP4",
            relay.rewrite("http://10.5.5.9:8080/videos/DCIM/100GOPRO/GX010001.MP4"),
        )
        assertEquals(
            "http://127.0.0.1:41234/abc123/gp/gpMediaMetadata?p=/100GOPRO/GX010001.MP4&t=scr",
            relay.rewrite("http://10.5.5.9:8080/gp/gpMediaMetadata?p=/100GOPRO/GX010001.MP4&t=scr"),
        )
    }

    @Test
    fun leavesOtherUrlsAlone() {
        assertEquals("https://cloud.example/x", relay.rewrite("https://cloud.example/x"))
        val direct = CameraRoute(null, com.openpro.camera.wifi.RouteMode.Default)
        assertEquals("http://10.5.5.9:8080/gp/gpMediaList", direct.rewrite("http://10.5.5.9:8080/gp/gpMediaList"))
    }
}
