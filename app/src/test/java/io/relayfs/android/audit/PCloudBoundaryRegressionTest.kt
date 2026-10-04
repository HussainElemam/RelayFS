package io.relayfs.android.audit

import io.relayfs.android.core.provider.pcloud.PCloudProvider
import io.relayfs.android.core.provider.pcloud.api.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class PCloudBoundaryRegressionTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var client: PCloudApiClient
    private lateinit var provider: PCloudProvider
    @Before fun setup() {
        server = MockWebServer(); server.start()
        client = PCloudApiClient(server.url("/").toString(), { "secret" })
        provider = PCloudProvider("pcloud_default", client)
    }
    @After fun teardown() { server.shutdown() }
    private fun json(body: String) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body)) }
    private val file = """{"fileid":1,"name":"note.md","size":8,"modified":"Thu, 01 Jan 1970 00:00:01 +0000","hash":10681749967730527559}"""
    @Test fun incompleteListingsFailClosedIncludingNestedFolders() = runBlocking {
        val invalid = listOf("{}", """{"result":0}""", """{"result":0,"metadata":{"folderid":10,"isfolder":true}}""",
            """{"result":0,"metadata":{"folderid":11,"isfolder":true,"contents":[]}}""",
            """{"result":0,"metadata":{"folderid":10,"isfolder":true,"contents":[{"folderid":20,"isfolder":true,"name":"nested"}]}}""",
            """{"result":0,"metadata":{"folderid":10,"isfolder":true,"contents":[{"fileid":1,"name":"missing-size","modified":"Thu, 01 Jan 1970 00:00:01 +0000"}]}}""")
        invalid.forEach { body -> json(body); assertTrue(body, provider.listFolder("d10", true).isFailure) }
        assertEquals(invalid.size, server.requestCount)
    }
    @Test fun invalidNamesDuplicatePathsAndMissingTimesFailClosed() = runBlocking {
        for (contents in listOf("$file,$file", file.replace("note.md", "../note.md"), file.replace("\"modified\":\"Thu, 01 Jan 1970 00:00:01 +0000\",", ""))) {
            json("""{"result":0,"metadata":{"folderid":10,"isfolder":true,"contents":[$contents]}}""")
            assertTrue(provider.listFolder("d10", true).isFailure)
        }
    }
    @Test fun explicitEmptyListingIsValidAndUnsignedHashIsPreserved() = runBlocking {
        json("""{"result":0,"metadata":{"folderid":10,"isfolder":true,"contents":[]}}""")
        assertTrue(provider.listFolder("d10", true).getOrThrow().isEmpty())
        json("""{"result":0,"metadata":{"folderid":10,"isfolder":true,"contents":[$file]}}""")
        assertEquals("10681749967730527559", provider.listFolder("d10", true).getOrThrow().single().contentHash)
    }
    @Test fun fileMetadataUsesStatAndRejectsWrongIdentity() = runBlocking {
        json("""{"result":0,"metadata":$file}""")
        val item = provider.getItemMetadata("f1").getOrThrow()
        assertEquals(8L, item.sizeBytes); assertEquals(1000L, item.modifiedTimeMillis)
        assertEquals("/stat?fileid=1", server.takeRequest().path)
        json("""{"result":0,"metadata":$file}""")
        assertTrue(provider.getItemMetadata("f2").isFailure)
    }
    @Test fun missingMetadataIsDistinctFromTransientNetworkFailure() = runBlocking {
        json("""{"result":2009,"error":"File not found"}""")
        assertTrue(provider.getItemMetadata("f1").exceptionOrNull() is java.io.FileNotFoundException)
        json("""{"result":5000,"error":"Retry later"}""")
        assertFalse(provider.getItemMetadata("f1").exceptionOrNull() is java.io.FileNotFoundException)
    }
    @Test fun rootAndNegativeFolderDeletionNeverReachNetwork() = runBlocking {
        for (id in listOf("d0", "0", "d-1", "-1")) assertTrue(id, provider.deleteItem(id, true).isFailure)
        assertEquals(0, server.requestCount)
    }
    @Test fun folderDeletionUsesServerEnforcedEmptyFolderEndpoint() = runBlocking {
        json("""{"result":2006,"error":"Folder is not empty"}""")
        assertTrue(provider.deleteItem("d10", true).isFailure)
        assertEquals("/deletefolder?folderid=10", server.takeRequest().path)
    }
    @Test fun malformedDeleteAcknowledgementIsNotSuccess() = runBlocking {
        json("{}"); assertTrue(provider.deleteItem("f1", false).isFailure)
    }
    @Test fun safeCreationRequestsNoPartialDataAndNonOverwritingName() = runBlocking {
        val source = temp.newFile("note.md").apply { writeText("original") }
        json("""{"result":0,"metadata":[$file]}""")
        provider.uploadNewFile("d10", source, "note.md").getOrThrow()
        val url = server.takeRequest().requestUrl!!
        assertEquals("1", url.queryParameter("nopartial")); assertEquals("1", url.queryParameter("renameifexists"))
    }
    @Test fun recoveryCopyMustMatchSourceVersionAndUseNoOverwrite() = runBlocking {
        json("""{"result":0,"metadata":$file}""")
        json("""{"result":0,"metadata":{"folderid":0,"isfolder":true,"contents":[{"folderid":99,"isfolder":true,"name":".relayfs-recovery"}]}}""")
        json("""{"result":0,"metadata":${file.replace("\"fileid\":1", "\"fileid\":2").replace("\"size\":8", "\"size\":7")}}""")
        assertTrue(provider.preserveFile("f1").isFailure)
        server.takeRequest(); server.takeRequest()
        assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("noover"))
    }
    @Test fun bearerTokenIsScopedToHttpsApiHostsAndAbsentFromDownloads() = runBlocking {
        val observed = mutableListOf<Request>()
        val transport = Interceptor { chain ->
            observed += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("content".toResponseBody()).build()
        }
        val http = OkHttpClient.Builder().addInterceptor(AuthInterceptor({ "secret" })).addInterceptor(transport).build()
        for (url in listOf("https://api.pcloud.com/userinfo", "https://eapi.pcloud.com/userinfo", "https://attacker.test/userinfo", "http://api.pcloud.com/userinfo")) http.newCall(Request.Builder().url(url).build()).execute().close()
        assertEquals(listOf("Bearer secret", "Bearer secret", null, null), observed.map { it.header("Authorization") })
        PCloudApiClient(tokenProvider = { "secret" }, customOkHttpClient = http).downloadFileStream("https://cdn.test/file", File(temp.root, "file")).getOrThrow()
        assertNull(observed.last().header("Authorization"))
    }
    @Test fun productionRejectsCleartextDownloadsBeforeAnyNetworkCall() = runBlocking {
        val production = PCloudApiClient(tokenProvider = { "secret" })
        assertTrue(production.downloadFileStream(server.url("/file").toString(), File(temp.root, "file")).isFailure)
        assertEquals(0, server.requestCount)
    }
    @Test fun cancellationInterruptsDownloadAndPreservesExistingFile() = runBlocking {
        val destination = temp.newFile("note.md").apply { writeText("valuable original") }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch(Dispatchers.IO) { client.downloadFileStream(server.url("/file").toString(), destination).getOrThrow() }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        job.cancel(); withTimeout(2000) { job.join() }
        assertEquals("valuable original", destination.readText())
        assertTrue(temp.root.listFiles()!!.none { it.name.endsWith(".sync_tmp") })
    }
    @Test fun cancellationStopsChunkedUploadBeforeSave() = runBlocking {
        val source = temp.newFile("large.bin").apply { writeBytes(ByteArray(2048)) }
        json("""{"result":0,"uploadid":1}""")
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch(Dispatchers.IO) { client.uploadFileChunked(10, source, "large.bin", chunkSize = 1024).getOrThrow() }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)); assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        job.cancel(); withTimeout(2000) { job.join() }
        assertEquals(2, server.requestCount)
    }
    @Test fun safeRenameCannotOverwriteARacingDestination() = runBlocking {
        json("""{"result":0,"metadata":$file}""")
        json("""{"result":2004,"error":"Destination exists"}""")
        assertTrue(provider.moveItem("f1", "d10", "renamed.md").isFailure)
        assertEquals("/stat?fileid=1", server.takeRequest().path)
        val request = server.takeRequest()
        assertTrue(request.path!!.startsWith("/copyfile?")); assertEquals("1", request.requestUrl!!.queryParameter("noover"))
        assertEquals(2, server.requestCount)
    }

    @Test fun invalidHashValuesDoNotCoerceToSuccess() {
        for (value in listOf("-1", "18446744073709551616", "\"not-a-hash\"", "[]")) {
            assertThrows(Exception::class.java) { client.json.decodeFromString<PCloudItemMetadata>("""{"hash":$value}""") }
        }
    }
}
