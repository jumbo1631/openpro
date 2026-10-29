package com.openpro.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaveTargetTest {
    @Test
    fun keysRoundTrip() {
        val gallery = SaveTarget.Gallery("1A2B-3C4D")
        val folder = SaveTarget.Folder("content://com.android.externalstorage.documents/tree/primary%3AGoPro", "GoPro")
        assertEquals(gallery, SaveTarget.parse(gallery.key))
        assertEquals(folder, SaveTarget.parse(folder.key))
    }

    @Test
    fun unknownKeysAreIgnored() {
        assertNull(SaveTarget.parse("somewhere"))
    }
}
