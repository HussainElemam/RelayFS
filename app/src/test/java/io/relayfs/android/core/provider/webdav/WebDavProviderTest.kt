package io.relayfs.android.core.provider.webdav

import io.relayfs.android.core.model.ProviderType
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("DEPRECATION")
class WebDavProviderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var provider: WebDavProvider
    private lateinit var okHttpClient: OkHttpClient

    private val sampleMultiStatusXml = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
    <d:response>
        <d:href>/remote.php/webdav/Notes/</d:href>
        <d:propstat>
            <d:prop>
                <d:resourcetype>
                    <d:collection/>
                </d:resourcetype>
                <d:getlastmodified>Tue, 15 Sep 2026 12:00:00 GMT</d:getlastmodified>
            </d:prop>
            <d:status>HTTP/1.1 200 OK</d:status>
        </d:propstat>
    </d:response>
    <d:response>
        <d:href>/remote.php/webdav/Notes/Ideas.md</d:href>
        <d:propstat>
            <d:prop>
                <d:resourcetype/>
                <d:getcontentlength>1024</d:getcontentlength>
                <d:getlastmodified>Tue, 15 Sep 2026 12:05:00 GMT</d:getlastmodified>
                <d:getetag>"abcd-1234"</d:getetag>
                <d:getcontenttype>text/markdown</d:getcontenttype>
            </d:prop>
            <d:status>HTTP/1.1 200 OK</d:status>
        </d:propstat>
    </d:response>
    <d:response>
        <d:href>/remote.php/webdav/Notes/SubFolder/</d:href>
        <d:propstat>
            <d:prop>
                <d:resourcetype>
                    <d:collection/>
                </d:resourcetype>
                <d:getlastmodified>Tue, 15 Sep 2026 12:10:00 GMT</d:getlastmodified>
            </d:prop>
            <d:status>HTTP/1.1 200 OK</d:status>
        </d:propstat>
    </d:response>
</d:multistatus>"""

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        provider = WebDavProvider(
            accountId = "webdav-acc-1",
            serverUrl = server.url("/remote.php/webdav/").toString(),
            username = "testuser",
            password = "testpassword",
            customOkHttpClient = okHttpClient
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testProviderMetadata() {
        assertEquals("webdav-acc-1", provider.accountId)
        assertEquals(ProviderType.WEBDAV, provider.providerType)
    }

    @Test
    fun testListFolderPropfind() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setHeader("Content-Type", "application/xml; charset=utf-8")
                .setBody(sampleMultiStatusXml)
        )

        val items = provider.listFolder("Notes").getOrThrow()
        val recorded = server.takeRequest()

        assertEquals("PROPFIND", recorded.method)
        assertEquals("1", recorded.getHeader("Depth"))
        assertTrue(recorded.getHeader("Authorization")?.startsWith("Basic ") == true)

        // The self entry (Notes/) should be filtered out, leaving Ideas.md and SubFolder
        assertEquals(2, items.size)

        val fileItem = items.first { !it.isDirectory }
        assertEquals("Ideas.md", fileItem.name)
        assertEquals(1024L, fileItem.sizeBytes)
        assertEquals("abcd-1234", fileItem.contentHash)
        assertEquals("text/markdown", fileItem.mimeType)
        assertFalse(fileItem.isDirectory)

        val dirItem = items.first { it.isDirectory }
        assertEquals("SubFolder", dirItem.name)
        assertTrue(dirItem.isDirectory)
    }

    @Test
    fun testDownloadFile() = runTest {
        val content = "# Test Markdown Content"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(content)
        )

        val destFile = File(tempFolder.root, "downloaded.md")
        provider.downloadFile("Notes/Ideas.md", destFile).getOrThrow()

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertTrue(recorded.path?.endsWith("Notes/Ideas.md") == true)
        assertEquals(content, destFile.readText())
    }

    @Test
    fun testUploadFile() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("ETag", "\"uploaded-etag-123\"")
        )

        val localFile = File(tempFolder.root, "local.md").apply {
            writeText("Local upload text")
        }

        val uploadedItem = provider.uploadFile("Notes", localFile, "local.md").getOrThrow()

        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertTrue(recorded.path?.endsWith("Notes/local.md") == true)
        assertEquals("Local upload text", recorded.body.readUtf8())

        assertEquals("local.md", uploadedItem.name)
        assertEquals(localFile.length(), uploadedItem.sizeBytes)
        assertFalse(uploadedItem.isDirectory)
        assertEquals("uploaded-etag-123", uploadedItem.contentHash)
    }

    @Test
    fun testCreateFolderMkcol() = runTest {
        server.enqueue(MockResponse().setResponseCode(201))

        val createdFolder = provider.createFolder("Notes", "NewFolder").getOrThrow()

        val recorded = server.takeRequest()
        assertEquals("MKCOL", recorded.method)
        assertTrue(recorded.path?.endsWith("Notes/NewFolder/") == true)

        assertEquals("NewFolder", createdFolder.name)
        assertTrue(createdFolder.isDirectory)
    }

    @Test
    fun testDeleteFile() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        provider.deleteItem("Notes/Trash.md", isDirectory = false).getOrThrow()

        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertTrue(recorded.path?.endsWith("Notes/Trash.md") == true)
    }

    @Test
    fun testMoveFile() = runTest {
        server.enqueue(MockResponse().setResponseCode(201))

        provider.moveItem("Notes/Old.md", "Notes", "New.md").getOrThrow()

        val recorded = server.takeRequest()
        assertEquals("MOVE", recorded.method)
        assertTrue(recorded.path?.endsWith("Notes/Old.md") == true)
        val destination = recorded.getHeader("Destination")
        assertNotNull(destination)
        assertTrue(destination!!.endsWith("Notes/New.md"))
        assertEquals("F", recorded.getHeader("Overwrite"))
    }

    @Test
    fun testVerifyConnectionSuccess() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setBody("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"></d:multistatus>""")
        )

        val result = provider.verifyConnection()
        assertTrue(result.isSuccess)
    }

    @Test
    fun testVerifyConnectionFailure() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = provider.verifyConnection()
        assertFalse(result.isSuccess)
    }
}
