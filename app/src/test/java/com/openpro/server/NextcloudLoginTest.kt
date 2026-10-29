package com.openpro.server

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import kotlinx.coroutines.channels.Channel
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class NextcloudLoginTest {
    private val server = MockWebServer()

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    private fun respond(code: Int, body: String = "") = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private val untrustedPage = """<!DOCTYPE html><html><body><h2>Access through untrusted domain</h2>
        <p>Please contact your administrator. If you are an administrator, edit the "trusted_domains" setting in config/config.php.</p></body></html>"""

    @Test
    fun usesTheEnteredAddressEvenWhenTheServerNamesAnother() = runBlocking {
        // Behind a proxy Nextcloud may call itself by an internal name, port or scheme.
        respond(200, """{"poll":{"token":"tok","endpoint":"https://nextcloud.internal:8443/login/v2/poll"},"login":"https://nextcloud.internal:8443/login/v2/flow/abc"}""")
        respond(404)
        respond(200, """{"server":"https://nextcloud.internal:8443","loginName":"user","appPassword":"secret"}""")

        val login = NextcloudLogin()
        val session = login.start(server.url("/").toString())
        val base = server.url("/")
        assertEquals(base.resolve("/login/v2/flow/abc").toString(), session.loginUrl)
        assertEquals(base.resolve("/login/v2/poll").toString(), session.pollEndpoint)

        val result = login.await(session)
        assertEquals("user", result.loginName)
        assertEquals("secret", result.appPassword)
        assertEquals("/index.php/login/v2", server.takeRequest().url.encodedPath)
        val poll = server.takeRequest()
        assertEquals("/login/v2/poll", poll.url.encodedPath)
        assertEquals("token=tok", poll.body?.utf8())
    }

    @Test
    fun keepsWaitingWhenThePollCantReachTheServer() = runBlocking {
        // Android cuts background apps off the network while the user is in the browser; here the
        // server is unreachable until the first failure is reported, then comes back.
        respond(200, """{"poll":{"token":"tok","endpoint":"${server.url("/login/v2/poll")}"},"login":"${server.url("/login/v2/flow/abc")}"}""")
        val login = NextcloudLogin()
        val session = login.start(server.url("/").toString())
        val port = server.port
        server.close()

        val back = MockWebServer()
        val wake = Channel<Unit>(Channel.UNLIMITED).apply { repeat(3) { trySend(Unit) } } // don't wait between polls
        val failures = mutableListOf<IOException>()
        try {
            val result = login.await(session, wake) { e ->
                failures += e
                if (failures.size == 1) {
                    back.enqueue(MockResponse.Builder().code(200).body("""{"server":"x","loginName":"user","appPassword":"secret"}""").build())
                    back.start(port)
                }
                true
            }
            assertEquals("secret", result.appPassword)
            assertEquals(1, failures.size)
        } finally {
            back.close()
        }
    }

    @Test
    fun givesUpWhenToldTo() = runBlocking {
        respond(200, """{"poll":{"token":"tok","endpoint":"${server.url("/login/v2/poll")}"},"login":"${server.url("/login/v2/flow/abc")}"}""")
        repeat(2) { server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()) }
        val login = NextcloudLogin()
        val session = login.start(server.url("/").toString())
        try {
            login.await(session) { false }
            fail("expected the connection failure")
        } catch (_: IOException) {
        }
    }

    @Test
    fun explainsAnUntrustedDomain() = runBlocking {
        respond(400, untrustedPage)
        try {
            NextcloudLogin().start(server.url("/").toString())
            fail("expected UntrustedDomainException")
        } catch (e: UntrustedDomainException) {
            assertEquals("${server.hostName}:${server.port}", e.address)
            assertTrue(e.message!!.contains("trusted_domains"))
        }
    }

    @Test
    fun explainsAnUntrustedDomainOnUploadRequests() = runBlocking {
        respond(400, untrustedPage)
        val client = ServerClient(ServerProfile(type = ServerType.WebDav, name = "nc", baseUrl = server.url("/").toString(), username = "user"), "pw")
        try {
            client.stat("/OpenPro")
            fail("expected UntrustedDomainException")
        } catch (e: UntrustedDomainException) {
            assertEquals("${server.hostName}:${server.port}", e.address)
        }
    }
}
