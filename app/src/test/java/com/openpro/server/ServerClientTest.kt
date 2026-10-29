package com.openpro.server

import com.openpro.batch.HttpStatusException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

class ServerClientTest {
    private val server = MockWebServer()

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    private fun nextcloud() = ServerClient(
        ServerProfile(type = ServerType.Nextcloud, name = "nc", baseUrl = server.url("/").toString(), username = "user@example.com", davUser = "user"),
        "app-pass",
    )

    private fun multistatus(vararg entries: String) =
        """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">${entries.joinToString("")}</d:multistatus>"""

    private fun file(href: String, size: Long, sha1: String? = null) = """<d:response><d:href>$href</d:href><d:propstat><d:prop>
        <d:resourcetype/><d:getcontentlength>$size</d:getcontentlength><d:getetag>"e"</d:getetag>
        ${sha1?.let { "<oc:checksums><oc:checksum>SHA1:$it MD5:abc</oc:checksum></oc:checksums>" } ?: ""}
        </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""

    private fun dir(href: String) = """<d:response><d:href>$href</d:href><d:propstat><d:prop>
        <d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""

    private fun respond(code: Int, body: String = "") = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun statParsesSizeAndChecksumAndUsesUserIdInPath() = runBlocking {
        respond(207, multistatus(file("/remote.php/dav/files/user/OpenPro/2026-08/GX010001.MP4", 1234, "ABCDEF")))
        val e = nextcloud().stat("/OpenPro/2026-08/GX010001.MP4")!!
        assertEquals("GX010001.MP4", e.name)
        assertEquals(1234, e.size)
        assertEquals("abcdef", e.sha1)
        val r = server.takeRequest()
        assertEquals("PROPFIND", r.method)
        assertEquals("/remote.php/dav/files/user/OpenPro/2026-08/GX010001.MP4", r.url.encodedPath)
        assertEquals("0", r.headers["Depth"])
        assertTrue(r.headers["Authorization"]!!.startsWith("Basic "))
    }

    @Test
    fun statReturnsNullFor404() = runBlocking {
        respond(404)
        assertNull(nextcloud().stat("/missing"))
    }

    @Test
    fun mkdirsCreatesEachLevelAndAcceptsExisting() = runBlocking {
        respond(405) // /OpenPro exists
        respond(201) // /OpenPro/2026-08 created
        nextcloud().mkdirs("/OpenPro/2026-08")
        assertEquals("/remote.php/dav/files/user/OpenPro/", server.takeRequest().url.encodedPath)
        val second = server.takeRequest()
        assertEquals("MKCOL", second.method)
        assertEquals("/remote.php/dav/files/user/OpenPro/2026-08/", second.url.encodedPath)
    }

    @Test
    fun listDecodesNamesAndSkipsTheFolderItself() = runBlocking {
        respond(207, multistatus(dir("/remote.php/dav/files/user/OpenPro/"), file("/remote.php/dav/files/user/OpenPro/a%20b.MP4", 5)))
        assertEquals(listOf("a b.MP4"), nextcloud().list("/OpenPro").map { it.name })
    }

    @Test
    fun chunkedUploadSendsDestinationAndFinishesWithMove() = runBlocking {
        val c = nextcloud()
        respond(201) // MKCOL session
        respond(201) // PUT chunk 1
        respond(201) // MOVE
        c.chunkedBegin("up1", "/OpenPro/x.MP4")
        val tmp = File.createTempFile("chunk", ".bin").apply { writeBytes(ByteArray(10) { it.toByte() }) }
        c.chunkedPut("up1", 1, "/OpenPro/x.MP4", ServerClient.FileRegionBody(tmp, 0, 10) {}, 10)
        c.chunkedFinish("up1", "/OpenPro/x.MP4", 10, "deadbeef", 1_700_000_000, overwrite = false)

        val mkcol = server.takeRequest()
        assertEquals("MKCOL", mkcol.method)
        assertEquals("/remote.php/dav/uploads/user/up1", mkcol.url.encodedPath)
        assertTrue(mkcol.headers["Destination"]!!.endsWith("/remote.php/dav/files/user/OpenPro/x.MP4"))
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/remote.php/dav/uploads/user/up1/1", put.url.encodedPath)
        assertEquals("10", put.headers["OC-Total-Length"])
        assertEquals(10L, put.bodySize)
        val move = server.takeRequest()
        assertEquals("MOVE", move.method)
        assertEquals("/remote.php/dav/uploads/user/up1/.file", move.url.encodedPath)
        assertEquals("SHA1:deadbeef", move.headers["OC-Checksum"])
        assertEquals("F", move.headers["Overwrite"])
        assertEquals("1700000000", move.headers["X-OC-Mtime"])
        tmp.delete()
        Unit
    }

    @Test
    fun existingChunksAreListedForResume() = runBlocking {
        respond(207, multistatus(dir("/remote.php/dav/uploads/user/up1/"), file("/remote.php/dav/uploads/user/up1/1", 100), file("/remote.php/dav/uploads/user/up1/2", 40)))
        assertEquals(mapOf(1 to 100L, 2 to 40L), nextcloud().chunkedExisting("up1"))
    }

    @Test
    fun goneSessionIsNull() = runBlocking {
        respond(404)
        assertNull(nextcloud().chunkedExisting("expired"))
    }

    @Test
    fun rejectedSignInIsAnHttpError() = runBlocking {
        respond(401)
        try {
            nextcloud().stat("/OpenPro")
            fail("expected 401")
        } catch (e: HttpStatusException) {
            assertEquals(401, e.code)
        }
    }

    @Test
    fun genericWebDavPathsAreRelativeToTheGivenUrl() = runBlocking {
        val c = ServerClient(ServerProfile(type = ServerType.WebDav, name = "nas", baseUrl = server.url("/dav/").toString(), username = "u"), "p")
        respond(201)
        val tmp = File.createTempFile("file", ".bin").apply { writeBytes(ByteArray(3)) }
        c.putFile("/OpenPro/a.MP4", tmp, null, null) {}
        assertEquals("/dav/OpenPro/a.MP4", server.takeRequest().url.encodedPath)
        tmp.delete()
        Unit
    }

    @Test
    fun findsMovedFilesByNameWithSearch() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(207).body(
                multistatus(file("/remote.php/dav/files/user/OpenPro/Moved%20by%20hand/2026-08-24_GX010849.MP4", 42, "abc")),
            ).build(),
        )
        val found = nextcloud().findByName("/OpenPro", listOf("2026-08-24_GX010849.MP4", "GX010849.MP4"))
        assertEquals(listOf("/OpenPro/Moved by hand/2026-08-24_GX010849.MP4"), found?.map { it.first })
        assertEquals(42L, found?.single()?.second?.size)

        val request = server.takeRequest()
        assertEquals("SEARCH", request.method)
        assertEquals("/remote.php/dav/", request.url.encodedPath)
        val body = request.body!!.utf8()
        assertTrue(body.contains("<d:href>/files/user/OpenPro</d:href>"))
        assertTrue(body.contains("<d:or>"))
        assertTrue(body.contains("<d:literal>GX010849.MP4</d:literal>"))
    }

    @Test
    fun searchingIsOptional() = runBlocking {
        server.enqueue(MockResponse.Builder().code(501).build())
        assertNull(nextcloud().findByName("/OpenPro", listOf("GX010849.MP4")))
    }
}
