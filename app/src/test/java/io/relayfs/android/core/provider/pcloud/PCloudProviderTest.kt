package io.relayfs.android.core.provider.pcloud

import io.relayfs.android.core.model.ChangeType
import io.relayfs.android.core.model.ProviderType
import io.relayfs.android.core.provider.pcloud.api.PCloudApiClient
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class PCloudProviderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var apiClient: PCloudApiClient
    private lateinit var provider: PCloudProvider

    private val testToken = "test_bearer_token_xyz"

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()

        val baseUrl = server.url("/").toString()
        apiClient = PCloudApiClient(
            baseUrl = baseUrl,
            tokenProvider = { testToken }
        )
        provider = PCloudProvider(
            accountId = "acc-test-123",
            apiClient = apiClient
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testVerifyConnectionSuccess() = runTest {
        val jsonResponse = """
            {
                "result": 0,
                "email": "user@example.com",
                "emailverified": true,
                "userid": 1234567,
                "quota": 10737418240,
                "usedquota": 524288000
            }
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonResponse)
        )

        val result = provider.verifyConnection()
        assertTrue(result.isSuccess)

        val account = result.getOrNull()
        assertNotNull(account)
        assertEquals("acc-test-123", account?.id)
        assertEquals("user@example.com", account?.email)
        assertEquals("user", account?.displayName)
        assertEquals(ProviderType.PCLOUD, account?.providerType)

        // Verify request received Authorization header
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded)
        assertNull(recorded?.getHeader("Authorization"))
        assertEquals("/userinfo", recorded?.path)
    }

    @Test
    fun testListFolderRecursive() = runTest {
        val jsonResponse = """
            {
                "result": 0,
                "metadata": {
                    "folderid": 0,
                    "name": "/",
                    "isfolder": true,
                    "contents": [
                        {
                            "fileid": 101,
                            "name": "root_note.md",
                            "isfolder": false,
                            "size": 120,
                            "modified": "Mon, 14 Sep 2026 10:00:00 +0000",
                            "hash": 987654321
                        },
                        {
                            "folderid": 200,
                            "name": "Notes",
                            "isfolder": true,
                            "contents": [
                                {
                                    "fileid": 201,
                                    "name": "daily.md",
                                    "isfolder": false,
                                    "size": 450,
                                    "modified": "Mon, 14 Sep 2026 11:00:00 +0000",
                                    "hash": 123456789
                                }
                            ]
                        }
                    ]
                }
            }
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonResponse)
        )

        val result = provider.listFolder("0", recursive = true)
        assertTrue(result.isSuccess)

        val items = result.getOrNull()
        assertNotNull(items)
        assertEquals(3, items?.size) // root_note.md, Notes/, Notes/daily.md

        val rootNote = items?.find { it.id == "f101" }
        assertNotNull(rootNote)
        assertEquals("root_note.md", rootNote?.name)
        assertEquals("/root_note.md", rootNote?.path)
        assertFalse(rootNote?.isDirectory == true)
        assertEquals(120L, rootNote?.sizeBytes)

        val dailyNote = items?.find { it.id == "f201" }
        assertNotNull(dailyNote)
        assertEquals("daily.md", dailyNote?.name)
        assertEquals("/Notes/daily.md", dailyNote?.path)
        assertEquals("123456789", dailyNote?.contentHash)
    }

    @Test
    fun testDeltaSyncParsing() = runTest {
        val jsonResponse = """
            {
                "result": 0,
                "diffid": 88888,
                "entries": [
                    {
                        "event": "createfile",
                        "time": "Mon, 14 Sep 2026 12:00:00 +0000",
                        "diffid": 88886,
                        "metadata": {
                            "fileid": 301,
                            "name": "created.md",
                            "path": "/Vault/created.md",
                            "isfolder": false,
                            "size": 250,
                            "modified": "Mon, 14 Sep 2026 12:00:00 +0000",
                            "hash": 11111
                        }
                    },
                    {
                        "event": "modifyfile",
                        "time": "Mon, 14 Sep 2026 12:05:00 +0000",
                        "diffid": 88887,
                        "metadata": {
                            "fileid": 302,
                            "name": "modified.md",
                            "path": "/Vault/modified.md",
                            "isfolder": false,
                            "size": 500,
                            "modified": "Mon, 14 Sep 2026 12:05:00 +0000",
                            "hash": 22222
                        }
                    },
                    {
                        "event": "deletefile",
                        "time": "Mon, 14 Sep 2026 12:10:00 +0000",
                        "diffid": 88888,
                        "metadata": {
                            "fileid": 303,
                            "name": "deleted.md",
                            "modified": "Mon, 14 Sep 2026 12:10:00 +0000",
                            "size": 0,
                            "path": "/Vault/deleted.md",
                            "isfolder": false,
                            "size": 0
                        }
                    }
                ]
            }
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonResponse)
        )

        val result = provider.getDeltaChanges(sinceToken = "88800")
        assertTrue(result.isSuccess)

        val delta = result.getOrNull()
        assertNotNull(delta)
        assertEquals("88888", delta?.newCursorToken)
        assertEquals(3, delta?.changes?.size)

        assertEquals(ChangeType.CREATED, delta?.changes?.get(0)?.changeType)
        assertEquals("f301", delta?.changes?.get(0)?.item?.id)

        assertEquals(ChangeType.MODIFIED, delta?.changes?.get(1)?.changeType)
        assertEquals("f302", delta?.changes?.get(1)?.item?.id)

        assertEquals(ChangeType.DELETED, delta?.changes?.get(2)?.changeType)
        assertEquals("f303", delta?.changes?.get(2)?.item?.id)
    }

    @Test
    fun testSinglePartUpload() = runTest {
        val testFile = tempFolder.newFile("small_test.txt")
        testFile.writeText("Hello RelayFS single-part upload!")

        val jsonResponse = """
            {
                "result": 0,
                "metadata": [
                    {
                        "fileid": 401,
                        "name": "small_test.txt",
                        "path": "/small_test.txt",
                        "isfolder": false,
                        "modified": "Mon, 14 Sep 2026 12:00:00 +0000",
                                "size": ${testFile.length()},
                        "modified": "Mon, 14 Sep 2026 12:00:00 +0000",
                        "hash": 55555
                    }
                ]
            }
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonResponse)
        )

        val result = provider.uploadFile(
            parentFolderId = "d0",
            file = testFile,
            remoteName = "small_test.txt"
        )

        assertTrue(result.isSuccess)
        val item = result.getOrNull()
        assertNotNull(item)
        assertEquals("f401", item?.id)
        assertEquals("small_test.txt", item?.name)

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded)
        assertTrue(recorded?.path?.startsWith("/uploadfile?folderid=0&filename=small_test.txt") == true)
    }

    @Test
    fun testSinglePartUploadPercentEncodesRemoteFilenameQueryParameter() = runTest {
        val testFile = tempFolder.newFile("source.txt")
        testFile.writeText("encoded filename")
        val remoteName = "Quarterly Notes + #1.md"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                        "result": 0,
                        "metadata": [
                            {
                                "fileid": 402,
                                "name": "$remoteName",
                                "path": "/$remoteName",
                                "isfolder": false,
                                "modified": "Mon, 14 Sep 2026 12:00:00 +0000",
                                "size": ${testFile.length()}
                            }
                        ]
                    }
                    """.trimIndent()
                )
        )

        val result = provider.uploadFile("d0", testFile, remoteName)

        assertTrue(result.isSuccess)
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded)
        assertEquals(remoteName, recorded?.requestUrl?.queryParameter("filename"))
        val encodedQuery = recorded?.requestUrl?.encodedQuery.orEmpty()
        assertTrue(encodedQuery.contains("filename=Quarterly%20Notes%20%2B%20%231.md"))
    }

    @Test
    fun testChunkedUpload() = runTest {
        // Create 6MB test file (which exceeds the 5MB threshold)
        val testFile = tempFolder.newFile("large_test.bin")
        val sixMb = 6 * 1024 * 1024
        val dummyData = ByteArray(1024) { 0x42 }
        testFile.outputStream().use { out ->
            for (i in 0 until (sixMb / 1024)) {
                out.write(dummyData)
            }
        }

        // 1. Response for /upload_create
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "result": 0, "uploadid": 99999 }""")
        )

        // 2. Responses for 3 chunks of 2MB (/upload_write)
        for (i in 0 until 3) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{ "result": 0 }""")
            )
        }

        // 3. Response for /upload_save
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                        "result": 0,
                        "metadata": [
                            {
                                "fileid": 501,
                                "name": "large_test.bin",
                                "path": "/large_test.bin",
                                "isfolder": false,
                                "modified": "Mon, 14 Sep 2026 12:00:00 +0000",
                                "size": $sixMb,
                                "hash": 77777
                            }
                        ]
                    }
                    """.trimIndent()
                )
        )

        var lastProgressBytes = 0L
        val result = provider.uploadFile(
            parentFolderId = "d0",
            file = testFile,
            remoteName = "large_test.bin",
            progress = { bytesWritten, _ ->
                lastProgressBytes = bytesWritten
            }
        )

        assertTrue(result.isSuccess)
        val item = result.getOrNull()
        assertNotNull(item)
        assertEquals("f501", item?.id)
        assertEquals(sixMb.toLong(), item?.sizeBytes)
        assertEquals(sixMb.toLong(), lastProgressBytes)
    }

    @Test
    fun testDownloadFileStreamAtomic() = runTest {
        val destFile = File(tempFolder.root, "downloaded_note.md")

        // 1. Response for /getfilelink
        val host = server.url("").host
        val port = server.url("").port
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "result": 0, "hosts": ["http://$host:$port"], "path": "/stream/content" }""")
        )

        // 2. Response for actual file content stream
        val expectedContent = "Downloaded file content stream from pCloud CDN!"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(expectedContent)
        )

        val result = provider.downloadFile("f601", destFile)
        assertTrue(result.isSuccess)

        assertTrue(destFile.exists())
        assertEquals(expectedContent, destFile.readText())

        // Verify staging file was renamed and does not remain
        val stagingFile = File(destFile.parentFile, ".downloaded_note.md.sync_tmp")
        assertFalse(stagingFile.exists())
    }

    @Test
    fun testDeleteBlankRemoteIdIsNoOp() = runTest {
        val result = provider.deleteItem("", isDirectory = false)

        assertTrue(result.isSuccess)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun testDeleteNonExistentFileReturnsSuccessIdempotent() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "result": 2009, "error": "File not found." }""")
        )

        val result = provider.deleteItem("f999", isDirectory = false)

        assertTrue(result.isSuccess)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun testDeleteNonExistentFolderReturnsSuccessIdempotent() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "result": 2005, "error": "Directory does not exist." }""")
        )

        val result = provider.deleteItem("d888", isDirectory = true)

        assertTrue(result.isSuccess)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun testDeleteInvalidRemoteIdResponseReturnsSuccessIdempotent() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "result": 2010, "error": "Invalid file ID." }""")
        )

        val result = provider.deleteItem("f777", isDirectory = false)

        assertTrue(result.isSuccess)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun testDeleteHttp404ReturnsSuccessIdempotent() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "result": 2009, "error": "Not Found" }""")
        )

        val result = provider.deleteItem("f777", isDirectory = false)

        assertTrue(result.isSuccess)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun testDeleteMalformedFolderIdFailsWithoutNetworkRequest() = runTest {
        val result = provider.deleteItem("not-a-folder-id", isDirectory = true)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun testRateLimitRetry() = runTest {
        // Enqueue HTTP 429 then HTTP 200
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "0")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "result": 0, "email": "retry@example.com", "userid": 123 }""")
        )

        val result = provider.verifyConnection()
        assertTrue(result.isSuccess)
        assertEquals("retry@example.com", result.getOrNull()?.email)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun testDatacenterRouting() {
        assertEquals("https://api.pcloud.com", PCloudApiClient.getBaseUrlForLocation(1))
        assertEquals("https://eapi.pcloud.com", PCloudApiClient.getBaseUrlForLocation(2))
        assertEquals("https://api.pcloud.com", PCloudApiClient.getBaseUrlForLocation(999)) // default
    }
}
