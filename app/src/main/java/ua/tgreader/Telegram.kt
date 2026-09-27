package ua.tgreader

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.IOException

data class Post(val id: Int, val date: String?, val text: String)

data class ChannelPage(
    val channel: String,
    val title: String,
    val posts: List<Post>,
    /** Pass as `before` to load older posts; null when there are none. */
    val prevBefore: Int?,
)

class ChannelException(message: String) : Exception(message)

/**
 * Reads a public Telegram channel through its web preview `https://t.me/s/<channel>`.
 * No account or API keys are needed, but only public channels with the preview enabled work.
 */
object Telegram {
    private val CHANNEL_RE = Regex("^[A-Za-z][A-Za-z0-9_]{3,31}$")
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"
    private val BLOCK_TAGS = setOf("div", "p", "blockquote", "li", "pre")

    /** Accepts `name`, `@name`, `t.me/name`, `https://t.me/s/name/123`, or any text containing such a link. */
    fun normalizeChannel(input: String): String {
        val raw = input.trim()
        val fromLink = Regex("""(?:t\.me|telegram\.me)/(?:s/)?([A-Za-z0-9_]+)""").find(raw)?.groupValues?.get(1)
        val name = fromLink ?: raw.removePrefix("@").substringBefore('/').substringBefore('?')
        if (!CHANNEL_RE.matches(name)) throw ChannelException("Некоректна назва каналу")
        return name
    }

    @Throws(IOException::class, ChannelException::class)
    fun fetch(channelInput: String, before: Int? = null): ChannelPage {
        val channel = normalizeChannel(channelInput)
        val url = "https://t.me/s/$channel" + (before?.let { "?before=$it" } ?: "")
        val response = Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .header("Accept-Language", "uk,en;q=0.8")
            .timeout(15_000)
            .execute()
        val html = response.body()
        // Private channels and channels without a preview redirect to t.me/<channel>.
        if (!response.url().path.startsWith("/s/") || !html.contains("tgme_channel_info")) {
            throw ChannelException("Канал не знайдено або він не публічний")
        }
        return parse(html, channel)
    }

    fun parse(html: String, channel: String): ChannelPage {
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst(".tgme_channel_info_header_title")?.text()?.trim().orEmpty()
        val prevBefore = doc.selectFirst("link[rel=prev]")?.attr("href")
            ?.let { Regex("before=(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val posts = doc.select(".tgme_widget_message[data-post]").mapNotNull { msg ->
            val id = msg.attr("data-post").substringAfterLast('/').toIntOrNull() ?: return@mapNotNull null
            val textEl = msg.select(".tgme_widget_message_text").firstOrNull { el ->
                el.parents().none { it.hasClass("tgme_widget_message_reply") }
            } ?: return@mapNotNull null
            val text = TextTools.clean(extractText(textEl))
            if (text.isEmpty()) return@mapNotNull null
            Post(id, msg.selectFirst("time[datetime]")?.attr("datetime"), text)
        }
        return ChannelPage(channel, title.ifEmpty { channel }, posts, prevBefore)
    }

    private fun extractText(root: Element): String {
        val sb = StringBuilder()
        fun walk(node: Node) {
            when (node) {
                is TextNode -> sb.append(node.wholeText)
                is Element -> {
                    if (node.hasClass("emoji") || node.tagName() == "tg-emoji") return
                    if (node.tagName() == "br") { sb.append('\n'); return }
                    val block = node !== root && node.tagName() in BLOCK_TAGS
                    if (block) sb.append('\n')
                    node.childNodes().forEach(::walk)
                    if (block) sb.append('\n')
                }
            }
        }
        walk(root)
        return sb.toString()
    }
}

object TextTools {
    private val EMOJI = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{1F1E6}-\\x{1F1FF}\\x{2B00}-\\x{2BFF}\\x{FE0F}\\x{200D}\\x{20E3}]+")
    private val URL = Regex("https?://\\S+")

    // Lines like "Підписатися" that channels append as a link to themselves.
    private val SUBSCRIBE_LINE = Regex("(?imu)^\\s*(підписатися|підпишись|підписуйтеся|підписуйтесь|subscribe|подписаться)[!.]?\\s*$")

    fun clean(text: String): String = text
        .replace(EMOJI, "")
        .replace(SUBSCRIBE_LINE, "")
        .replace(Regex("[ \\t\\u00A0]+"), " ")
        .replace(Regex(" *\\n *"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    /** Splits a post into short pieces so pause/resume and skipping feel instant. */
    fun chunks(text: String, max: Int = 250): List<String> {
        val sentences = text.replace(URL, "посилання")
            .split(Regex("(?<=[.!?…])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        for (sentence in sentences) {
            var s = sentence
            while (s.length > max) {
                var cut = s.lastIndexOf(", ", max)
                if (cut < max / 2) cut = s.lastIndexOf(' ', max)
                if (cut < 1) cut = max
                out += s.substring(0, cut + 1).trim()
                s = s.substring(cut + 1).trim()
            }
            if (s.isNotEmpty()) out += s
        }
        return out
    }
}
