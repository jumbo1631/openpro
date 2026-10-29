package com.openpro.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextcloudAppAccountTest {
    @Test
    fun splitsLoginNameFromServer() {
        assertEquals("user" to "cloud.example.com", NextcloudAppAccount.parse("user@cloud.example.com"))
        assertEquals("user" to "192.168.0.10:8080/nextcloud", NextcloudAppAccount.parse("user@192.168.0.10:8080/nextcloud"))
    }

    @Test
    fun loginNamesMayBeEmailAddresses() {
        assertEquals("user@example.org" to "cloud.example.com", NextcloudAppAccount.parse("user@example.org@cloud.example.com"))
    }

    @Test
    fun rejectsNamesWithoutAServer() {
        assertNull(NextcloudAppAccount.parse("user"))
        assertNull(NextcloudAppAccount.parse("user@"))
        assertNull(NextcloudAppAccount.parse("@cloud.example.com"))
    }
}
