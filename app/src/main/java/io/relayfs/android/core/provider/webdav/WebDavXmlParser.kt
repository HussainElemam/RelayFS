package io.relayfs.android.core.provider.webdav

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Parsed metadata item from an RFC 4918 WebDAV PROPFIND XML response.
 */
data class WebDavResource(
    val href: String,
    val displayName: String,
    val isDirectory: Boolean,
    val contentLength: Long,
    val lastModifiedMillis: Long,
    val etag: String?,
    val contentType: String?
)

/**
 * Parser for RFC 4918 WebDAV XML responses (such as PROPFIND multistatus).
 */
object WebDavXmlParser {

    private val rfc1123Format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val rfc850Format = SimpleDateFormat("EEEE, dd-MMM-yy HH:mm:ss zzz", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val asctimeFormat = SimpleDateFormat("EEE MMM d HH:mm:ss yyyy", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun parseMultiStatus(inputStream: InputStream): List<WebDavResource> {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(inputStream, "UTF-8")

        val results = mutableListOf<WebDavResource>()
        var eventType = parser.eventType

        var currentHref: String? = null
        var currentDisplayName: String? = null
        var isDirectory = false
        var contentLength: Long = 0L
        var lastModifiedMillis: Long = System.currentTimeMillis()
        var etag: String? = null
        var contentType: String? = null

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val tagName = parser.name?.substringAfter(':')?.lowercase()

            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (tagName) {
                        "response" -> {
                            currentHref = null
                            currentDisplayName = null
                            isDirectory = false
                            contentLength = 0L
                            lastModifiedMillis = System.currentTimeMillis()
                            etag = null
                            contentType = null
                        }
                        "href" -> {
                            currentHref = parser.nextText().trim()
                        }
                        "displayname" -> {
                            currentDisplayName = parser.nextText().trim()
                        }
                        "getcontentlength" -> {
                            contentLength = parser.nextText().trim().toLongOrNull() ?: 0L
                        }
                        "getlastmodified" -> {
                            lastModifiedMillis = parseHttpDate(parser.nextText().trim())
                        }
                        "collection" -> {
                            isDirectory = true
                        }
                        "getetag" -> {
                            etag = parser.nextText().trim().removeSurrounding("\"")
                        }
                        "getcontenttype" -> {
                            contentType = parser.nextText().trim()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (tagName == "response" && currentHref != null) {
                        val decodedHref = try {
                            URLDecoder.decode(currentHref, "UTF-8")
                        } catch (_: Exception) {
                            currentHref
                        }

                        // Derive display name from href if missing
                        val resolvedName = currentDisplayName?.takeIf { it.isNotBlank() }
                            ?: decodedHref.trimEnd('/').substringAfterLast('/')

                        if (resolvedName.isNotBlank()) {
                            results.add(
                                WebDavResource(
                                    href = decodedHref,
                                    displayName = resolvedName,
                                    isDirectory = isDirectory,
                                    contentLength = contentLength,
                                    lastModifiedMillis = lastModifiedMillis,
                                    etag = etag,
                                    contentType = contentType
                                )
                            )
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        return results
    }

    fun parseHttpDate(dateString: String): Long {
        if (dateString.isBlank()) return System.currentTimeMillis()

        return try {
            rfc1123Format.parse(dateString)?.time
                ?: rfc850Format.parse(dateString)?.time
                ?: asctimeFormat.parse(dateString)?.time
                ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }
}

