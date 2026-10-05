package com.music.spotui.playback

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

/**
 * High-performance, streaming RSS parser for podcast feeds.
 * Extracts episode metadata including title, enclosure audio URL,
 * MIME type, publication date, and duration without loading unnecessary XML nodes into memory.
 */
data class ParsedPodcastFeed(
    val title: String? = null,
    val author: String? = null,
    val imageUrl: String? = null,
    val description: String? = null,
    val episodes: List<PodcastRssEpisode> = emptyList()
)

object PodcastRssParser {

    fun parse(xmlString: String): List<PodcastRssEpisode> = parseFeed(xmlString).episodes

    fun parse(inputStream: InputStream): List<PodcastRssEpisode> = parseFeed(inputStream).episodes

    fun parse(source: InputSource): List<PodcastRssEpisode> = parseFeed(source).episodes

    fun parseFeed(xmlString: String): ParsedPodcastFeed {
        if (xmlString.isBlank()) return ParsedPodcastFeed()
        return StringReader(xmlString).use { reader ->
            parseFeed(InputSource(reader))
        }
    }

    fun parseFeed(inputStream: InputStream): ParsedPodcastFeed {
        return parseFeed(InputSource(inputStream))
    }

    fun parseFeed(source: InputSource): ParsedPodcastFeed {
        val episodes = mutableListOf<PodcastRssEpisode>()
        val handler = RssHandler(episodes)
        try {
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = true
            }
            val saxParser = factory.newSAXParser()
            saxParser.parse(source, handler)
        } catch (e: Exception) {
            // Parsing completed or encountered an issue
        }
        return handler.buildFeed()
    }

    /**
     * Parses duration strings commonly found in podcast feeds:
     * - Seconds ("3450", "3450.5")
     * - MM:SS ("57:30")
     * - HH:MM:SS ("01:23:45")
     * Returns duration in milliseconds or null if unparseable.
     */
    fun parseDurationToMs(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()

        if (trimmed.contains(':')) {
            val parts = trimmed.split(':')
            return try {
                when (parts.size) {
                    3 -> {
                        val hours = parts[0].trim().toLong()
                        val minutes = parts[1].trim().toLong()
                        val seconds = parts[2].trim().toDouble().toLong()
                        ((hours * 3600) + (minutes * 60) + seconds) * 1000L
                    }
                    2 -> {
                        val minutes = parts[0].trim().toLong()
                        val seconds = parts[1].trim().toDouble().toLong()
                        ((minutes * 60) + seconds) * 1000L
                    }
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }

        return trimmed.toDoubleOrNull()?.let { (it * 1000).toLong() }
    }

    /**
     * Decodes basic HTML entities and numeric character references commonly found in RSS text.
     */
    fun decodeHtmlEntities(input: String): String {
        if (!input.contains('&')) return input
        var result = input
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")

        if (result.contains("&#")) {
            result = Regex("""&#([0-9]+);""").replace(result) { mr ->
                mr.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: mr.value
            }
            result = Regex("""&#x([0-9a-fA-F]+);""").replace(result) { mr ->
                mr.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: mr.value
            }
        }
        return result
    }

    private class RssHandler(
        private val episodes: MutableList<PodcastRssEpisode>
    ) : DefaultHandler() {

        private var inItem = false
        private var currentTag: String? = null

        // Channel metadata
        private var channelTitle: StringBuilder = StringBuilder()
        private var channelAuthor: StringBuilder = StringBuilder()
        private var channelDescription: StringBuilder = StringBuilder()
        private var channelImageUrl: String? = null
        private var inChannelImage = false

        // Item metadata
        private var currentGuid: StringBuilder = StringBuilder()
        private var currentTitle: StringBuilder = StringBuilder()
        private var currentPubDate: StringBuilder = StringBuilder()
        private var currentDuration: StringBuilder = StringBuilder()

        private var currentEnclosureUrl: String? = null
        private var currentEnclosureType: String? = null
        private var currentEnclosureLength: Long? = null

        fun buildFeed(): ParsedPodcastFeed {
            return ParsedPodcastFeed(
                title = channelTitle.toString().trim().ifBlank { null }?.let { decodeHtmlEntities(it) },
                author = channelAuthor.toString().trim().ifBlank { null }?.let { decodeHtmlEntities(it) },
                imageUrl = channelImageUrl?.trim()?.ifBlank { null },
                description = channelDescription.toString().trim().ifBlank { null }?.let { decodeHtmlEntities(it) },
                episodes = episodes
            )
        }

        override fun startElement(
            uri: String?,
            localName: String?,
            qName: String?,
            attributes: Attributes?
        ) {
            val rawQ = (qName ?: "").lowercase()
            val rawLocal = (localName ?: "").lowercase()
            val tag = if (rawLocal.isNotBlank()) rawLocal else rawQ

            if (tag == "item" || rawQ.endsWith(":item")) {
                inItem = true
                currentTag = null
                currentGuid.setLength(0)
                currentTitle.setLength(0)
                currentPubDate.setLength(0)
                currentDuration.setLength(0)
                currentEnclosureUrl = null
                currentEnclosureType = null
                currentEnclosureLength = null
                return
            }

            if (!inItem) {
                val isImage = tag == "image" || rawQ.endsWith(":image")
                val isAuthor = tag == "author" || rawQ.endsWith(":author") || tag == "managingeditor" || rawQ.endsWith(":creator")
                val isDescription = tag == "description" || rawQ.endsWith(":summary") || rawQ.endsWith(":description")

                when {
                    isImage -> {
                        val href = attributes?.getValue("href") ?: attributes?.getValue("url")
                        if (!href.isNullOrBlank()) {
                            if (channelImageUrl == null) channelImageUrl = href.trim()
                        } else {
                            inChannelImage = true
                        }
                    }
                    tag == "title" && !inChannelImage -> currentTag = "channel_title"
                    isAuthor -> currentTag = "channel_author"
                    isDescription -> currentTag = "channel_description"
                    inChannelImage && (tag == "url" || tag == "href") -> currentTag = "channel_image_url"
                }
                return
            }

            val isDuration = tag == "duration" || rawQ.endsWith(":duration")
            when {
                tag == "title" -> currentTag = "title"
                tag == "guid" -> currentTag = "guid"
                tag == "pubdate" || rawQ.endsWith(":pubdate") -> currentTag = "pubdate"
                isDuration -> currentTag = "duration"
                tag == "enclosure" || rawQ.endsWith(":enclosure") -> {
                    currentEnclosureUrl = attributes?.getValue("url")
                    currentEnclosureType = attributes?.getValue("type")
                    currentEnclosureLength = attributes?.getValue("length")?.toLongOrNull()
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (currentTag == null) return
            if (!inItem) {
                when (currentTag) {
                    "channel_title" -> channelTitle.append(ch, start, length)
                    "channel_author" -> channelAuthor.append(ch, start, length)
                    "channel_description" -> channelDescription.append(ch, start, length)
                    "channel_image_url" -> {
                        if (channelImageUrl == null) {
                            val url = String(ch, start, length).trim()
                            if (url.startsWith("http")) channelImageUrl = url
                        }
                    }
                }
                return
            }
            when (currentTag) {
                "title" -> currentTitle.append(ch, start, length)
                "guid" -> currentGuid.append(ch, start, length)
                "pubdate" -> currentPubDate.append(ch, start, length)
                "duration" -> currentDuration.append(ch, start, length)
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val rawQ = (qName ?: "").lowercase()
            val rawLocal = (localName ?: "").lowercase()
            val tag = if (rawLocal.isNotBlank()) rawLocal else rawQ

            if ((tag == "item" || rawQ.endsWith(":item")) && inItem) {
                inItem = false
                val rawTitle = currentTitle.toString().trim()
                val title = decodeHtmlEntities(rawTitle)
                val guid = currentGuid.toString().trim().ifBlank { null }
                val pubDate = currentPubDate.toString().trim().ifBlank { null }
                val duration = parseDurationToMs(currentDuration.toString().trim())

                episodes.add(
                    PodcastRssEpisode(
                        guid = guid,
                        title = title,
                        pubDate = pubDate,
                        durationMs = duration,
                        enclosureUrl = currentEnclosureUrl?.trim()?.ifBlank { null },
                        enclosureType = currentEnclosureType?.trim()?.ifBlank { null },
                        enclosureLength = currentEnclosureLength
                    )
                )
                currentTag = null
                return
            }

            if (!inItem) {
                if (tag == "image" || rawQ.endsWith(":image")) inChannelImage = false
                if (currentTag == "channel_title" && tag == "title") currentTag = null
                if (currentTag == "channel_author" && (tag == "author" || rawQ.endsWith(":author") || tag == "managingeditor")) currentTag = null
                if (currentTag == "channel_description" && (tag == "description" || rawQ.endsWith(":summary") || rawQ.endsWith(":description"))) currentTag = null
                if (currentTag == "channel_image_url" && (tag == "url" || tag == "href")) currentTag = null
                return
            }

            if (inItem && (currentTag == tag || rawQ.endsWith(":$currentTag"))) {
                currentTag = null
            }
        }
    }
}
