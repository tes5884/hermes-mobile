package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.ui.components.ChatMarkdown
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkifyTest {
    private fun urls(t: String) = ChatMarkdown.findUrls(t).map { t.substring(it.start, it.end) }

    @Test fun trailingPunctuationIsNotPartOfUrl() {
        assertEquals(listOf("https://a.com/x"), urls("see https://a.com/x."))
        assertEquals(listOf("https://a.com/x"), urls("see https://a.com/x, then"))
        assertEquals(listOf("https://a.com/x"), urls("(see https://a.com/x)"))
        assertEquals(listOf("https://a.com/x"), urls("really https://a.com/x?!"))
    }
    @Test fun balancedParenStaysInUrl() {
        assertEquals(listOf("https://en.wikipedia.org/wiki/Foo_(bar)"), urls("https://en.wikipedia.org/wiki/Foo_(bar)."))
    }
    @Test fun wwwGetsHttps() {
        val s = ChatMarkdown.findUrls("go to www.example.com/a now")
        assertEquals(1, s.size)
        assertEquals("https://www.example.com/a", s[0].url)
    }
    @Test fun noFalsePositives() {
        assertEquals(emptyList<String>(), urls("no links here, example.com alone"))
        assertEquals(emptyList<String>(), urls("javascript:alert(1) ftp://x.com"))
    }
    @Test fun multipleUrls() {
        assertEquals(listOf("http://a.com", "https://b.org/p"), urls("http://a.com and https://b.org/p."))
    }
    @Test fun markdownLinkIsOneLinkNotTwo() {
        val node = ChatMarkdown.parse("[site](https://a.com/x) and https://b.com.")
        val out = com.nousresearch.hermesmobile.ui.components.renderMarkdownInline(node.firstChild!!, androidx.compose.ui.graphics.Color.Blue, androidx.compose.ui.graphics.Color.Gray) {}
        assertEquals("site and https://b.com.", out.text)
        assertEquals(listOf("https://a.com/x", "https://b.com"), out.getLinkAnnotations(0, out.length).map { (it.item as androidx.compose.ui.text.LinkAnnotation.Url).url })
    }
}
