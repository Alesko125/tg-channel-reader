package ua.tgreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramTest {
    private val sample = """
        <html><head><link rel="prev" href="/s/testchan?before=10"></head><body>
        <div class="tgme_channel_info_header_title"><span dir="auto">Тестовий канал</span></div>
        <div class="tgme_widget_message js-widget_message" data-post="testchan/11">
          <div class="tgme_widget_message_text js-message_text" dir="auto"><tg-emoji><i class="emoji"><b>🔥</b></i></tg-emoji> Привіт,&nbsp;світ!<br/><br/>Друга <b>частина</b> 👍<br><a href="https://x.com">посилання</a><br><br><a href="https://t.me/testchan">Підписатися</a></div>
          <a class="tgme_widget_message_date"><time datetime="2026-09-01T10:00:00+00:00">10:00</time></a>
        </div>
        <div class="tgme_widget_message js-widget_message" data-post="testchan/12">
          <a class="tgme_widget_message_reply"><div class="tgme_widget_message_text js-message_reply_text">Цитата</div></a>
          <div class="tgme_widget_message_text js-message_text">Відповідь</div>
        </div>
        <div class="tgme_widget_message js-widget_message" data-post="testchan/13">
          <div class="tgme_widget_message_photo_wrap"></div>
        </div>
        </body></html>
    """.trimIndent()

    @Test fun parsesMetaAndPosts() {
        val page = Telegram.parse(sample, "testchan")
        assertEquals("Тестовий канал", page.title)
        assertEquals(10, page.prevBefore)
        assertEquals(listOf(11, 12), page.posts.map { it.id })
        assertEquals("Привіт, світ!\n\nДруга частина\nпосилання", page.posts[0].text)
        assertEquals("2026-09-01T10:00:00+00:00", page.posts[0].date)
        assertEquals("Відповідь", page.posts[1].text)
        assertNull(page.posts[1].date)
    }

    @Test fun parsesRealPage() {
        val html = javaClass.classLoader!!.getResource("durov.html")!!.readText()
        val page = Telegram.parse(html, "durov")
        assertEquals("Pavel Durov", page.title)
        assertTrue(page.posts.size > 10)
        assertTrue(page.posts.all { it.text.isNotBlank() && it.date != null })
    }

    @Test fun normalizesChannelInput() {
        for (v in listOf("durov", "@durov", "t.me/durov", "https://t.me/durov", "https://t.me/s/durov/5",
            " https://telegram.me/durov?x=1 ", "Дивись: https://t.me/durov/123 класний пост")) {
            assertEquals(v, "durov", Telegram.normalizeChannel(v))
        }
    }

    @Test(expected = ChannelException::class) fun rejectsGarbage() {
        Telegram.normalizeChannel("bad name")
    }

    @Test fun chunksAreShortAndReplaceLinks() {
        val long = "Слово ".repeat(200) + "кінець. Друге речення! Дивись https://example.com/a?b=c"
        val chunks = TextTools.chunks(long, max = 100)
        assertTrue(chunks.all { it.length <= 101 })
        assertEquals("Дивись посилання", chunks.last())
    }

    @Test fun parsesPhotos() {
        val html = javaClass.classLoader!!.getResource("durov.html")!!.readText()
        val page = Telegram.parse(html, "durov")
        val withPhoto = page.posts.filter { it.photo != null }
        assertTrue(withPhoto.isNotEmpty())
        assertTrue(withPhoto.all { it.photo!!.startsWith("https://") })
        assertTrue(page.posts.all { it.channel == "durov" })
    }

    @Test fun findsChannelsInText() {
        val text = """
            Мої канали:
            https://t.me/Northern_Sich_ukr
            t.me/s/war_monitor/123
            @kpszsu — повітряні сили
            ukrpravda_news
            пошта test@example.com не канал
        """.trimIndent()
        assertEquals(listOf("Northern_Sich_ukr", "war_monitor", "kpszsu", "ukrpravda_news"), Telegram.findChannels(text))
    }

    @Test fun filterSkipsAdsWordsAndShortPosts() {
        fun p(t: String) = Post(1, null, t, "c")
        val s = AppSettings(skipAds = true, filterWords = listOf("Розіграш"), minLength = 10)
        assertTrue(PostFilter.passes(p("Звичайна новина дня"), s))
        assertFalse(PostFilter.passes(p("Класний сервіс\n#реклама"), s))
        assertFalse(PostFilter.passes(p("На правах реклами: купуйте"), s))
        assertFalse(PostFilter.passes(p("Великий розіграш призів"), s))
        assertFalse(PostFilter.passes(p("Коротко"), s))
        assertTrue(PostFilter.passes(p("Класний сервіс\n#реклама"), s.copy(skipAds = false)))
        // "реклама" inside ordinary news is not an ad marker.
        assertTrue(PostFilter.passes(p("Уряд заборонив рекламу казино"), s))
    }

    @Test fun catalogNamesAreValid() {
        val names = Catalog.sections.flatMap { it.channels }.map { it.name }
        assertEquals(names.size, names.map { it.lowercase() }.toSet().size)
        names.forEach { assertEquals(it, Telegram.normalizeChannel(it)) }
        assertTrue(Catalog.sections.first().channels.any { it.name == "Northern_Sich_ukr" })
    }
}
